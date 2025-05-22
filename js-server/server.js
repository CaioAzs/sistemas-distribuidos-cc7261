const zmq = require('zeromq');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');

class Server {
    constructor(serverId, brokerAddress = 'tcp://localhost:5556', pubAddress = 'tcp://localhost:5557') {
        this.serverId = serverId;
        this.brokerAddress = brokerAddress;
        this.pubAddress = pubAddress;
        
        // Storage
        this.posts = [];
        this.privateMessages = [];
        this.followers = {};
        
        // Files
        this.dataFile = path.join(__dirname, 'logs', `server_${serverId}_data.json`);
        this.logFile = path.join(__dirname, 'logs', `server_${serverId}_log.txt`);
        
        // Criar diretório se não existir
        this.createLogDirectory();
        
        console.log(`\n=== SERVER ${serverId} STARTING ===`);
        this.loadData();
    }
    
    createLogDirectory() {
        const fs = require('fs');
        const logDir = path.join(__dirname, 'logs');
        
        if (!fs.existsSync(logDir)) {
            fs.mkdirSync(logDir, { recursive: true });
            console.log(`Created directory: ${logDir}`);
        }
    }

    async initialize() {
        try {
            // Socket REP para comunicação com o broker
            this.socket = new zmq.Reply();
            await this.socket.connect(this.brokerAddress);
            console.log(`Server ${this.serverId} connected to broker at ${this.brokerAddress}`);
            
            // Socket PUB para publicação de posts/mensagens
            this.pubSocket = new zmq.Publisher();
            await this.pubSocket.connect(this.pubAddress);
            console.log(`Server ${this.serverId} connected to post proxy at ${this.pubAddress}`);
            
            // Socket PUB para enviar replicações (conecta ao XSUB do proxy na porta 6000)
            this.replicationPubSocket = new zmq.Publisher();
            await this.replicationPubSocket.connect('tcp://localhost:6000');
            console.log(`Server ${this.serverId} connected to replication publisher (port 6000)`);
            
            // Socket SUB para receber replicações (conecta ao XPUB do proxy na porta 6001)
            this.replicationSubSocket = new zmq.Subscriber();
            await this.replicationSubSocket.connect('tcp://localhost:6001');
            this.replicationSubSocket.subscribe('replication');
            console.log(`Server ${this.serverId} subscribed to replication (port 6001)`);
            
            // Aguardar um pouco para garantir conexões
            await new Promise(resolve => setTimeout(resolve, 1000));
            
            // Iniciar listener de replicação
            this.startReplicationListener();
            
            console.log(`\n=== SERVER ${this.serverId} READY FOR REQUESTS ===\n`);
            
        } catch (error) {
            console.error(`Failed to initialize server ${this.serverId}:`, error);
            throw error;
        }
    }
    
    startReplicationListener() {
        (async () => {
            try {
                for await (const [topic, msg] of this.replicationSubSocket) {
                    try {
                        const data = JSON.parse(msg.toString());
                        
                        // Ignorar próprias replicações
                        if (data.source_server === this.serverId) {
                            continue;
                        }
                        
                        let replicated = false;
                        switch (data.type) {
                            case 'post':
                                replicated = this.addReplicatedPost(data.payload);
                                break;
                            case 'message':
                                replicated = this.addReplicatedMessage(data.payload);
                                break;
                            case 'follow':
                                replicated = this.addReplicatedFollow(data.payload.follower_id, data.payload.target_user_id);
                                break;
                        }
                        
                        if (replicated) {
                            this.log(`Replication received from Server ${data.source_server}: ${data.type}`);
                        }
                        
                    } catch (error) {
                        console.error(`Error processing replication in server ${this.serverId}:`, error);
                    }
                }
            } catch (error) {
                console.error(`Replication listener error in server ${this.serverId}:`, error);
            }
        })();
    }
    
    async replicateData(type, payload) {
        try {
            const replicationData = {
                type: type,
                payload: payload,
                source_server: this.serverId,
                timestamp: Date.now(),
                id: uuidv4()
            };
            
            // Enviar replicação
            await this.replicationPubSocket.send(['replication', JSON.stringify(replicationData)]);
            
            this.log(`Replicated ${type} data: ${JSON.stringify(payload)} - Replication ID: ${replicationData.id}`);
            
        } catch (error) {
            console.error(`Replication failed in server ${this.serverId}:`, error);
            this.log(`ERROR: Replication failed for ${type}: ${error.message}`);
        }
    }

    async createPost(userId, content, clientTimestamp) {
        const post = {
            id: uuidv4(),
            user_id: userId,
            content: content,
            server_id: this.serverId,
            created_at: Date.now(),
            client_timestamp: clientTimestamp // INCLUIR TIMESTAMP DO CLIENTE
        };
        
        this.posts.push(post);
        this.saveData();
        
        console.log(`\nPOST CREATED in Server ${this.serverId}:`);
        console.log(`   User: ${userId}`);
        console.log(`   Content: ${content}`);
        console.log(`   ID: ${post.id}`);
        console.log(`   Client timestamp: ${new Date(clientTimestamp).toLocaleTimeString()}`);
        console.log(`   Total posts in server: ${this.posts.length}`);
        
        this.log(`Post created: ID=${post.id}, User=${userId}, Content=${content}, ClientTime=${clientTimestamp}`);
        
        // Publicar o post para notificações
        try {
            const postMessage = {
                type: "new_post",
                post: post,
                server_timestamp: Date.now() // INCLUIR TIMESTAMP DO SERVIDOR
            };
            await this.pubSocket.send(userId + ":" + JSON.stringify(postMessage));
        } catch (error) {
            console.error(`Error publishing post notification:`, error);
        }
        
        // Replicar para outros servidores
        await this.replicateData('post', post);
        
        return post;
    }
    
    addReplicatedPost(post) {
        // Verificar se já existe
        const exists = this.posts.some(p => p.id === post.id);
        if (!exists) {
            this.posts.push(post);
            this.saveData();
            this.log(`Post replicated: ID=${post.id} from server ${post.server_id}`);
            return true;
        }
        return false;
    }
    
    async sendPrivateMessage(senderId, receiverId, content, clientTimestamp) {
        const message = {
            id: uuidv4(),
            sender_id: senderId,
            receiver_id: receiverId,
            content: content,
            server_id: this.serverId,
            created_at: Date.now(),
            client_timestamp: clientTimestamp // INCLUIR TIMESTAMP DO CLIENTE
        };
        
        this.privateMessages.push(message);
        this.saveData();
        
        console.log(`\nPRIVATE MESSAGE SENT in Server ${this.serverId}:`);
        console.log(`   From: ${senderId} -> To: ${receiverId}`);
        console.log(`   Content: ${content}`);
        console.log(`   ID: ${message.id}`);
        console.log(`   Client timestamp: ${new Date(clientTimestamp).toLocaleTimeString()}`);
        console.log(`   Total messages in server: ${this.privateMessages.length}`);
        
        this.log(`Private message sent: ID=${message.id}, From=${senderId}, To=${receiverId}, Content=${content}, ClientTime=${clientTimestamp}`);
        
        // Publicar notificação para o destinatário
        try {
            const notification = {
                type: "private_message",
                sender_id: senderId,
                content: content,
                created_at: Date.now(),
                client_timestamp: clientTimestamp, // TIMESTAMP DO REMETENTE
                server_timestamp: Date.now() // TIMESTAMP DO SERVIDOR
            };
            await this.pubSocket.send(receiverId + ":PM:" + JSON.stringify(notification));
        } catch (error) {
            console.error(`Error publishing private message notification:`, error);
        }
        
        // Replicar para outros servidores
        await this.replicateData('message', message);
        
        return message;
    }
    
    addReplicatedMessage(message) {
        const exists = this.privateMessages.some(m => m.id === message.id);
        if (!exists) {
            this.privateMessages.push(message);
            this.saveData();
            this.log(`Message replicated: ID=${message.id} from ${message.sender_id} to ${message.receiver_id}`);
            return true;
        }
        return false;
    }
    
    followUser(followerId, targetUserId, clientTimestamp) {
        if (!this.followers[targetUserId]) {
            this.followers[targetUserId] = [];
        }
        
        if (!this.followers[targetUserId].includes(followerId)) {
            this.followers[targetUserId].push(followerId);
            this.saveData();
            
            console.log(`\nFOLLOW RELATIONSHIP CREATED in Server ${this.serverId}:`);
            console.log(`   ${followerId} is now following ${targetUserId}`);
            console.log(`   ${targetUserId} now has ${this.followers[targetUserId].length} followers`);
            console.log(`   Client timestamp: ${new Date(clientTimestamp).toLocaleTimeString()}`);
            
            this.log(`Follow relationship: ${followerId} -> ${targetUserId}, ClientTime=${clientTimestamp}`);
            
            // Replicar para outros servidores
            this.replicateData('follow', { follower_id: followerId, target_user_id: targetUserId });
            
            return true;
        }
        return false;
    }
    
    addReplicatedFollow(followerId, targetUserId) {
        if (!this.followers[targetUserId]) {
            this.followers[targetUserId] = [];
        }
        
        if (!this.followers[targetUserId].includes(followerId)) {
            this.followers[targetUserId].push(followerId);
            this.saveData();
            this.log(`Follow relationship replicated: ${followerId} -> ${targetUserId}`);
            return true;
        }
        return false;
    }
    
    getFollowing(userId) {
        const following = [];
        for (const [targetId, followers] of Object.entries(this.followers)) {
            if (followers.includes(userId)) {
                following.push(targetId);
            }
        }
        return following;
    }
    
    getAllPosts() {
        return [...this.posts].sort((a, b) => b.created_at - a.created_at);
    }
    
    // Método para verificar status da replicação
    getReplicationStatus() {
        return {
            server_id: this.serverId,
            posts_count: this.posts.length,
            messages_count: this.privateMessages.length,
            followers_count: Object.keys(this.followers).length,
            posts_by_server: this.posts.reduce((acc, post) => {
                acc[post.server_id] = (acc[post.server_id] || 0) + 1;
                return acc;
            }, {}),
            messages_by_server: this.privateMessages.reduce((acc, msg) => {
                acc[msg.server_id] = (acc[msg.server_id] || 0) + 1;
                return acc;
            }, {})
        };
    }
    
    saveData() {
        try {
            const data = {
                posts: this.posts,
                privateMessages: this.privateMessages,
                followers: this.followers,
                server_id: this.serverId,
                last_updated: new Date().toISOString()
            };
            fs.writeFileSync(this.dataFile, JSON.stringify(data, null, 2));
        } catch (error) {
            console.error(`Error saving data:`, error);
        }
    }
    
    loadData() {
        try {
            if (fs.existsSync(this.dataFile)) {
                const data = JSON.parse(fs.readFileSync(this.dataFile, 'utf8'));
                this.posts = data.posts || [];
                this.privateMessages = data.privateMessages || [];
                this.followers = data.followers || {};
                
                // Log de posts por servidor apenas no arquivo de log
                const postsByServer = this.posts.reduce((acc, post) => {
                    acc[post.server_id] = (acc[post.server_id] || 0) + 1;
                    return acc;
                }, {});
                this.log(`Data loaded: ${this.posts.length} posts, ${this.privateMessages.length} messages. Posts by server: ${JSON.stringify(postsByServer)}`);
            } else {
                this.log(`No existing data file. Starting fresh.`);
            }
        } catch (error) {
            console.error(`Error loading data:`, error);
        }
    }
    
    log(message) {
        try {
            const timestamp = new Date().toISOString();
            const logMessage = `${timestamp} - SERVER ${this.serverId} - ${message}\n`;
            fs.appendFileSync(this.logFile, logMessage);
        } catch (error) {
            console.error(`Error writing to log:`, error);
        }
    }

    async run() {
        console.log(`\nServer ${this.serverId} processing requests...\n`);
        
        for await (const [msg] of this.socket) {
            try {
                const message = JSON.parse(msg.toString());
                const clientTimestamp = message.client_timestamp || Date.now();
                const userId = message.user_id || message.follower_id || message.sender_id || 'unknown';
                
                console.log(`Server ${this.serverId} received request: ${message.type} from user ${userId}`);
                console.log(`   Client timestamp: ${new Date(clientTimestamp).toLocaleTimeString()}`);
                
                let response = { status: "error", message: "Unknown request type" };
                
                switch (message.type) {
                    case "create_post":
                        const post = await this.createPost(message.user_id, message.content, clientTimestamp);
                        response = {
                            status: "success",
                            post: post,
                            message: "Post created successfully",
                            server_id: this.serverId
                        };
                        break;
                        
                    case "follow_user":
                        const followed = this.followUser(message.follower_id, message.target_user_id, clientTimestamp);
                        response = {
                            status: "success",
                            followed: followed,
                            message: followed 
                                ? `Now following user ${message.target_user_id}` 
                                : `Already following user ${message.target_user_id}`,
                            server_id: this.serverId
                        };
                        break;
                        
                    case "get_following":
                        const following = this.getFollowing(message.user_id);
                        response = {
                            status: "success",
                            following: following,
                            message: `Following ${following.length} users`,
                            server_id: this.serverId
                        };
                        break;
                        
                    case "get_all_posts":
                        const allPosts = this.getAllPosts();
                        response = {
                            status: "success",
                            posts: allPosts,
                            message: `Found ${allPosts.length} posts`,
                            server_id: this.serverId,
                            replication_status: this.getReplicationStatus()
                        };
                        break;
                        
                    case "send_private_message":
                        await this.sendPrivateMessage(message.sender_id, message.receiver_id, message.content, clientTimestamp);
                        response = {
                            status: "success",
                            message: `Message sent to user ${message.receiver_id}`,
                            server_id: this.serverId
                        };
                        break;
                        
                    case "get_replication_status":
                        response = {
                            status: "success",
                            replication_status: this.getReplicationStatus(),
                            server_id: this.serverId
                        };
                        break;
                }
                
                await this.socket.send(JSON.stringify(response));
                console.log(`Server ${this.serverId} sent response: ${response.status}\n`);
                
            } catch (error) {
                console.error(`Error processing request in server ${this.serverId}:`, error);
                await this.socket.send(JSON.stringify({
                    status: "error",
                    message: error.message,
                    server_id: this.serverId
                }));
            }
        }
    }
}

async function main() {
    try {
        const serverId = process.argv[2] || "1";
        const brokerAddress = process.argv[3] || "tcp://localhost:5556";
        const pubAddress = process.argv[4] || "tcp://localhost:5557";
        
        console.log(`\nStarting Server ${serverId} with configuration:`);
        console.log(`   Broker Address: ${brokerAddress}`);
        console.log(`   Publishing Address: ${pubAddress}`);
        console.log(`   Replication Pub: tcp://localhost:6000`);
        console.log(`   Replication Sub: tcp://localhost:6001`);
        console.log(`   Server Time: ${new Date().toLocaleTimeString()}`);
        
        const server = new Server(serverId, brokerAddress, pubAddress);
        await server.initialize();
        await server.run();
    } catch (error) {
        console.error(`Server error:`, error);
        process.exit(1);
    }
}

main();