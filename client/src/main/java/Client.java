import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.io.FileInputStream;
import java.util.Properties;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.Date;
import java.text.SimpleDateFormat;

public class Client {
    private final String userId;
    private final ZContext context;
    private final ZMQ.Socket reqSocket;
    private final ZMQ.Socket subSocket;
    private final List<JSONObject> receivedPosts;
    private final List<JSONObject> receivedMessages;
    private final ExecutorService executorService;
    private boolean isRunning;
    private Set<String> following;
    private boolean inMenu = false;

    // LOGICAL CLOCK - Relógio lógico do cliente
    private Date logicalClock;
    private final SimpleDateFormat timeFormat;
    private final String logFile;

    public Client(String userId, String brokerAddress, String subAddress) {
        this.userId = userId;
        this.context = new ZContext();
        this.reqSocket = context.createSocket(SocketType.REQ);
        this.subSocket = context.createSocket(SocketType.SUB);
        this.receivedPosts = new ArrayList<>();
        this.receivedMessages = new ArrayList<>();
        this.executorService = Executors.newSingleThreadExecutor();
        this.isRunning = true;
        this.following = new HashSet<>();
        this.timeFormat = new SimpleDateFormat("HH:mm:ss");

        // CONFIGURAR ARQUIVO DE LOG
        this.logFile = "logs/client_" + userId + "_log.txt";
        createLogDirectory();

        // INICIALIZAR RELÓGIO LÓGICO - horário fixo na inicialização
        this.logicalClock = new Date();
        System.out.println("LOGICAL CLOCK initialized at: " + timeFormat.format(logicalClock));
        log("CLIENT STARTED - Logical clock initialized at: " + timeFormat.format(logicalClock));

        // Adicionar a si mesmo à lista de seguindo (para ver próprios posts)
        this.following.add(userId);

        // Conectar ao broker para requests
        reqSocket.connect(brokerAddress);
        clearScreen();
        System.out.println("Client " + userId + " connected to broker at " + brokerAddress);
        System.out.println("Client logical clock: " + timeFormat.format(logicalClock));
        log("Connected to broker at: " + brokerAddress);

        // Conectar ao proxy para assinatura de posts
        subSocket.connect(subAddress);
        log("Connected to subscriber at: " + subAddress);

        // Assinar os próprios posts
        subscribeToUser(userId);

        // Assinar mensagens privadas dirigidas a este usuário
        subscribeToPMs();

        // Iniciar thread para receber posts e mensagens
        startMessageListener();

        // Carregar usuários que já segue
        loadFollowing();
    }

    // CRIAR DIRETÓRIO DE LOGS
    private void createLogDirectory() {
        try {
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
                System.out.println("Created log directory: logs");
            }
        } catch (Exception e) {
            System.err.println("Error creating log directory: " + e.getMessage());
        }
    }

    // MÉTODO DE LOG
    private void log(String message) {
        try {
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String clockTime = timeFormat.format(logicalClock);
            String logMessage = String.format("[%s] [CLOCK: %s] CLIENT %s - %s%n",
                    timestamp, clockTime, userId, message);

            java.nio.file.Files.write(
                    java.nio.file.Paths.get(logFile),
                    logMessage.getBytes(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Error writing to log: " + e.getMessage());
        }
    }

    // INCREMENTAR RELÓGIO LÓGICO - adiciona 1 segundo a cada operação
    private void incrementLogicalClock() {
        long currentTime = logicalClock.getTime();
        logicalClock = new Date(currentTime + 1000); // +1 segundo
        System.out.println("Clock incremented to: " + timeFormat.format(logicalClock));
        log("Clock incremented to: " + timeFormat.format(logicalClock));
    }

    // AJUSTAR RELÓGIO MANUALMENTE
    private void adjustClock(int seconds) {
        long currentTime = logicalClock.getTime();
        Date oldTime = new Date(currentTime);
        logicalClock = new Date(currentTime + (seconds * 1000));
        String adjustment = seconds > 0 ? "advanced" : "delayed";
        System.out.println("Clock " + adjustment + " by " + Math.abs(seconds) + " seconds");
        System.out.println("New clock time: " + timeFormat.format(logicalClock));
        log("Manual clock adjustment: " + adjustment + " by " + Math.abs(seconds) + "s. Old: " +
                timeFormat.format(oldTime) + ", New: " + timeFormat.format(logicalClock));
    }

    // SINCRONIZAR COM SERVIDOR quando remetente está no futuro
    private void synchronizeWithServer(long serverTime, String source) {
        Date oldTime = new Date(logicalClock.getTime());
        logicalClock = new Date(serverTime);

        System.out.println("\n*** CLOCK SYNC WITH SERVER ***");
        System.out.println("Old time: " + timeFormat.format(oldTime));
        System.out.println("New time: " + timeFormat.format(logicalClock));
        System.out.println("*** SYNC COMPLETE ***\n");

        log("CLOCK SYNCHRONIZATION - Trigger: " + source +
                ", Server time: " + timeFormat.format(new Date(serverTime)) +
                ", New time: " + timeFormat.format(logicalClock));
    }

    // Método para limpar a tela
    private void clearScreen() {
        try {
            // Para Windows
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                // Para Unix/Linux/Mac
                System.out.print("\033[2J\033[H");
                System.out.flush();
            }
        } catch (Exception e) {
            // Se não conseguir limpar a tela, apenas adiciona algumas linhas em branco
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
    }

    // Método para pausar e aguardar enter do usuário
    private void pauseForUser() {
        System.out.println("\nPress ENTER to continue...");
        try {
            System.in.read();
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void loadFollowing() {
        log("Loading existing following relationships from server");
        JSONObject response = getFollowing();
        if (response.has("status") && response.getString("status").equals("success")) {
            if (response.has("following")) {
                JSONArray followingArray = response.getJSONArray("following");
                for (int i = 0; i < followingArray.length(); i++) {
                    String followedUserId = followingArray.getString(i);
                    following.add(followedUserId);
                    subscribeToUser(followedUserId);
                }
                System.out.println("Loaded following list: " + following);
                log("Loaded " + followingArray.length() + " existing following relationships: " + following);
            }
        } else {
            log("No existing followers");
        }
    }

    private void subscribeToUser(String targetUserId) {
        String subscriptionTopic = targetUserId + ":";
        subSocket.subscribe(subscriptionTopic.getBytes(ZMQ.CHARSET));
        System.out.println("*** SUBSCRIBED to posts from user " + targetUserId + " ***");
        System.out.println("    Topic: '" + subscriptionTopic + "'");
        log("Subscribed to posts from user " + targetUserId + " (topic: '" + subscriptionTopic + "')");
    }

    private void subscribeToPMs() {
        // Assinar mensagens privadas enviadas para este usuário
        String pmTopic = userId + ":PM:";
        subSocket.subscribe(pmTopic.getBytes(ZMQ.CHARSET));
        System.out.println("Subscribed to private messages");
        log("Subscribed to private messages (topic: '" + pmTopic + "')");
    }

private void startMessageListener() {
    executorService.submit(() -> {
        System.out.println("*** MESSAGE LISTENER STARTED ***");
        while (isRunning) {
            try {
                String messageStr = subSocket.recvStr();
                System.out.println("*** RECEIVED MESSAGE: "
                        + messageStr.substring(0, Math.min(100, messageStr.length())) + "...");

                // Verificar se é mensagem privada ou post
                if (messageStr.contains(":PM:")) {
                    // É uma mensagem privada
                    int prefixEnd = messageStr.indexOf(":PM:") + 4;
                    String jsonStr = messageStr.substring(prefixEnd);
                    JSONObject message = new JSONObject(jsonStr);

                    // VERIFICAR SE A MENSAGEM É PARA ESTE CLIENTE
                    String topicReceiverId = messageStr.substring(0, messageStr.indexOf(":PM:"));

                    // Só processar se a mensagem for para este cliente
                    if (!topicReceiverId.equals(this.userId)) {
                        System.out.println("*** IGNORED: Private message not for this user ***");
                        continue;
                    }

                    synchronized (receivedMessages) {
                        receivedMessages.add(message);
                    }

                    log("Received private message from user " + message.getString("sender_id") +
                            ": " + message.getString("content"));

                    // VERIFICAR SE PRECISA SINCRONIZAR RELÓGIO
                    long senderTime = message.has("client_timestamp") ? message.getLong("client_timestamp")
                            : message.getLong("created_at");

                    if (senderTime > logicalClock.getTime() && message.has("server_timestamp")) {
                        synchronizeWithServer(message.getLong("server_timestamp"),
                                "Private Message from " + message.getString("sender_id"));
                    }

                    String notification = "New private message from User " +
                            message.getString("sender_id") + ": " +
                            message.getString("content");

                    // Se não estiver no menu, mostrar imediatamente
                    if (!inMenu) {
                        System.out.println("\n[NOTIFICATION] " + notification);
                    }

                } else {
                    // É um post normal
                    int separatorIndex = messageStr.indexOf(":");
                    if (separatorIndex > 0) {
                        String postUserId = messageStr.substring(0, separatorIndex);
                        String jsonPart = messageStr.substring(separatorIndex + 1);

                        System.out.println("*** POST NOTIFICATION from user " + postUserId + " ***");

                        JSONObject postData = new JSONObject(jsonPart);
                        if (postData.has("type") && postData.getString("type").equals("new_post")) {
                            JSONObject post = postData.getJSONObject("post");

                            // *** CORREÇÃO: NÃO PROCESSAR PRÓPRIOS POSTS ***
                            String postAuthor = post.getString("user_id");
                            if (postAuthor.equals(this.userId)) {
                                System.out.println("*** IGNORED: Own post - no self-notification ***");
                                continue;
                            }

                            // VERIFICAR SE ESTOU SEGUINDO ESTE USUÁRIO
                            boolean shouldProcess = following.contains(postAuthor);

                            if (!shouldProcess) {
                                System.out.println("*** IGNORED: Post from user I don't follow ***");
                                continue;
                            }

                            synchronized (receivedPosts) {
                                receivedPosts.add(post);
                            }

                            log("Received post notification from user " + post.getString("user_id") +
                                    ": " + post.getString("content"));

                            // VERIFICAR SE PRECISA SINCRONIZAR RELÓGIO
                            long senderTime = post.has("client_timestamp") ? post.getLong("client_timestamp")
                                    : post.getLong("created_at");

                            if (senderTime > logicalClock.getTime() && postData.has("server_timestamp")) {
                                synchronizeWithServer(postData.getLong("server_timestamp"),
                                        "Post from " + post.getString("user_id"));
                            }

                            String notification = "New post from User " +
                                    post.getString("user_id") + ": " +
                                    post.getString("content");

                            // Se não estiver no menu, mostrar imediatamente
                            if (!inMenu) {
                                System.out.println("\n[NOTIFICATION] " + notification);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    System.err.println("Error in message listener: " + e.getMessage());
                }
            }
        }
        System.out.println("Message listener stopped");
    });
}

    public void close() {
        isRunning = false;
        log("CLIENT SHUTTING DOWN");
        reqSocket.close();
        subSocket.close();
        executorService.shutdown();
        context.close();
    }

    public JSONObject createPost(String content) {
        // INCREMENTAR RELÓGIO antes da operação
        incrementLogicalClock();

        // Criar mensagem para o servidor
        JSONObject message = new JSONObject();
        message.put("type", "create_post");
        message.put("user_id", userId);
        message.put("content", content);
        message.put("request_id", UUID.randomUUID().toString());
        message.put("client_timestamp", logicalClock.getTime()); // Enviar timestamp

        System.out.println("Sending post with timestamp: " + timeFormat.format(logicalClock));
        log("Creating post: '" + content + "' with timestamp: " + timeFormat.format(logicalClock));

        // Enviar mensagem
        reqSocket.send(message.toString().getBytes(ZMQ.CHARSET), 0);

        // Receber resposta
        byte[] reply = reqSocket.recv(0);
        String replyStr = new String(reply, ZMQ.CHARSET);
        JSONObject response = new JSONObject(replyStr);

        boolean success = response.has("status") && response.getString("status").equals("success");
        log("Post creation " + (success ? "successful" : "failed") +
                ". Server response: " + response.optString("message", "No message"));

        return response;
    }

    public JSONObject sendPrivateMessage(String receiverId, String content) {
        // INCREMENTAR RELÓGIO antes da operação
        incrementLogicalClock();

        // Criar mensagem para o servidor
        JSONObject message = new JSONObject();
        message.put("type", "send_private_message");
        message.put("sender_id", userId);
        message.put("receiver_id", receiverId);
        message.put("content", content);
        message.put("request_id", UUID.randomUUID().toString());
        message.put("client_timestamp", logicalClock.getTime()); // Enviar timestamp

        System.out.println("Sending private message with timestamp: " + timeFormat.format(logicalClock));
        log("Sending private message to user " + receiverId + ": '" + content +
                "' with timestamp: " + timeFormat.format(logicalClock));

        // Enviar mensagem
        reqSocket.send(message.toString().getBytes(ZMQ.CHARSET), 0);

        // Receber resposta
        byte[] reply = reqSocket.recv(0);
        String replyStr = new String(reply, ZMQ.CHARSET);
        JSONObject response = new JSONObject(replyStr);

        boolean success = response.has("status") && response.getString("status").equals("success");
        log("Private message " + (success ? "sent successfully" : "failed") +
                " to user " + receiverId + ". Server response: " + response.optString("message", "No message"));

        return response;
    }

    public JSONObject followUser(String targetUserId) {
        // INCREMENTAR RELÓGIO antes da operação
        incrementLogicalClock();

        // Criar mensagem para o servidor
        JSONObject message = new JSONObject();
        message.put("type", "follow_user");
        message.put("follower_id", userId);
        message.put("target_user_id", targetUserId);
        message.put("request_id", UUID.randomUUID().toString());
        message.put("client_timestamp", logicalClock.getTime()); // Enviar timestamp

        System.out.println("Sending follow request with timestamp: " + timeFormat.format(logicalClock));
        log("Following user " + targetUserId + " with timestamp: " + timeFormat.format(logicalClock));

        // Enviar mensagem
        reqSocket.send(message.toString().getBytes(ZMQ.CHARSET), 0);

        // Receber resposta
        byte[] reply = reqSocket.recv(0);
        String replyStr = new String(reply, ZMQ.CHARSET);
        JSONObject response = new JSONObject(replyStr);

        if (response.has("status") && response.getString("status").equals("success")) {
            // Adicionar à lista de seguindo
            following.add(targetUserId);
            // Assinar os posts deste usuário
            subscribeToUser(targetUserId);
            log("Successfully following user " + targetUserId + ". Now following " + following.size() + " users");
        } else {
            log("Failed to follow user " + targetUserId + ". Server response: " +
                    response.optString("message", "No message"));
        }

        return response;
    }

    public JSONObject getFollowing() {
        // INCREMENTAR RELÓGIO antes da operação
        incrementLogicalClock();

        // Criar mensagem para o servidor
        JSONObject message = new JSONObject();
        message.put("type", "get_following");
        message.put("user_id", userId);
        message.put("request_id", UUID.randomUUID().toString());
        message.put("client_timestamp", logicalClock.getTime()); // Enviar timestamp

        log("Requesting following list with timestamp: " + timeFormat.format(logicalClock));

        // Enviar mensagem
        reqSocket.send(message.toString().getBytes(ZMQ.CHARSET), 0);

        // Receber resposta
        byte[] reply = reqSocket.recv(0);
        String replyStr = new String(reply, ZMQ.CHARSET);
        JSONObject response = new JSONObject(replyStr);

        if (response.has("status") && response.getString("status").equals("success")) {
            int followingCount = response.has("following") ? response.getJSONArray("following").length() : 0;
            log("Retrieved following list: " + followingCount + " users");
        } else {
            log("Failed to get following list. Server response: " + response.optString("message", "No message"));
        }

        return response;
    }

    public JSONObject getAllPosts() {
        // INCREMENTAR RELÓGIO antes da operação
        incrementLogicalClock();

        // Criar mensagem para o servidor
        JSONObject message = new JSONObject();
        message.put("type", "get_all_posts");
        message.put("user_id", userId);
        message.put("request_id", UUID.randomUUID().toString());
        message.put("client_timestamp", logicalClock.getTime()); // Enviar timestamp

        System.out.println("Getting all posts with timestamp: " + timeFormat.format(logicalClock));
        log("Requesting all posts with timestamp: " + timeFormat.format(logicalClock));

        // Enviar mensagem
        reqSocket.send(message.toString().getBytes(ZMQ.CHARSET), 0);

        // Receber resposta
        byte[] reply = reqSocket.recv(0);
        String replyStr = new String(reply, ZMQ.CHARSET);
        JSONObject response = new JSONObject(replyStr);

        if (response.has("status") && response.getString("status").equals("success")) {
            int postsCount = response.has("posts") ? response.getJSONArray("posts").length() : 0;
            log("Retrieved all posts: " + postsCount + " posts found");
        } else {
            log("Failed to get all posts. Server response: " + response.optString("message", "No message"));
        }

        return response;
    }

    public List<JSONObject> getReceivedPosts() {
        synchronized (receivedPosts) {
            return new ArrayList<>(receivedPosts);
        }
    }

    public List<JSONObject> getReceivedMessages() {
        synchronized (receivedMessages) {
            return new ArrayList<>(receivedMessages);
        }
    }

    // Método para exibir os posts com formatação
    private void displayPosts(List<JSONObject> posts) {
        clearScreen();
        log("Displaying " + posts.size() + " posts to user");
        System.out.println("POSTS DISPLAY");
        System.out.println("Current client time: " + timeFormat.format(logicalClock));
        System.out.println("=".repeat(50));

        if (posts.isEmpty()) {
            System.out.println("No posts to display");
            pauseForUser();
            return;
        }

        // Ordenar posts por timestamp (mais recente primeiro)
        posts.sort(new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject p1, JSONObject p2) {
                return Long.compare(p2.getLong("created_at"), p1.getLong("created_at"));
            }
        });

        for (int i = 0; i < posts.size(); i++) {
            JSONObject post = posts.get(i);
            System.out.println("\nPost #" + (i + 1));
            System.out.println("User: " + post.getString("user_id"));
            System.out.println("Content: " + post.getString("content"));
            Date postDate = new Date(post.getLong("created_at"));
            System.out.println("Time: " + postDate);

            // Marcar posts de usuários que você segue
            if (following.contains(post.getString("user_id"))) {
                System.out.println("Status: You are following this user");
            }
            System.out.println("-".repeat(40));
        }

        pauseForUser();
    }

    private void displayMessages() {
        clearScreen();
        log("Displaying " + getReceivedMessages().size() + " private messages to user");
        System.out.println("PRIVATE MESSAGES");
        System.out.println("Current client time: " + timeFormat.format(logicalClock));
        System.out.println("=".repeat(50));

        List<JSONObject> messages = getReceivedMessages();
        if (messages.isEmpty()) {
            System.out.println("No messages to display");
            pauseForUser();
            return;
        }

        for (int i = 0; i < messages.size(); i++) {
            JSONObject msg = messages.get(i);
            System.out.println("\nMessage #" + (i + 1));
            System.out.println("From: User " + msg.getString("sender_id"));
            System.out.println("Content: " + msg.getString("content"));
            Date msgDate = new Date(msg.getLong("created_at"));
            System.out.println("Time: " + msgDate);
            System.out.println("-".repeat(40));
        }

        pauseForUser();
    }

    private void showMenu() {
        clearScreen();
        inMenu = true;

        System.out.println("SOCIAL NETWORK CLIENT - User: " + userId);
        System.out.println("Current Time: " + timeFormat.format(logicalClock));
        System.out.println("=".repeat(50));
        System.out.println("1. Create post");
        System.out.println("2. Follow user");
        System.out.println("3. Show notifications");
        System.out.println("4. Show all posts");
        System.out.println("5. Send private message");
        System.out.println("6. Show received messages");
        System.out.println("8. Advance clock (+1 second)");
        System.out.println("9. Delay clock (-1 second)");
        System.out.println("7. Exit");
        System.out.println("=".repeat(50));
        System.out.print("Choose an option: ");
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Carregar configurações do arquivo
        String brokerAddress = "tcp://localhost:5555";
        String subAddress = "tcp://localhost:5558";

        try {
            Properties props = new Properties();

            // Verificar se o arquivo existe
            File configFile = new File("config.properties");
            if (configFile.exists()) {
                FileInputStream fis = new FileInputStream(configFile);
                props.load(fis);
                fis.close();

                brokerAddress = props.getProperty("broker.address", brokerAddress);
                subAddress = props.getProperty("subscriber.address", subAddress);

                System.out.println("Loaded configuration:");
                System.out.println("Broker address: " + brokerAddress);
                System.out.println("Subscriber address: " + subAddress);
            } else {
                System.out.println("Config file not found. Using default addresses.");
            }
        } catch (Exception e) {
            System.out.println("Error loading config: " + e.getMessage());
            System.out.println("Using default addresses");
        }

        // Verificar se o ID do usuário foi fornecido como propriedade do sistema
        String userId = System.getProperty("user.id");
        if (userId == null || userId.isEmpty()) {
            System.out.print("Enter user ID: ");
            userId = scanner.nextLine();
        } else {
            System.out.println("Using provided user ID: " + userId);
        }

        Client client = new Client(userId, brokerAddress, subAddress);

        // Pequena pausa para inicialização
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean running = true;
        while (running) {
            client.showMenu();

            String option = scanner.nextLine().trim();
            client.inMenu = false;

            switch (option) {
                case "1":
                    client.clearScreen();
                    System.out.println("CREATE NEW POST");
                    System.out.println("Current time: " + client.timeFormat.format(client.logicalClock));
                    System.out.println("=".repeat(30));
                    System.out.print("Enter post content: ");
                    String content = scanner.nextLine();
                    JSONObject response = client.createPost(content);
                    boolean success = response.has("status") && response.getString("status").equals("success");
                    System.out.println(success ? "Post created successfully!" : "Failed to create post");
                    client.log("User action: Create post - " + (success ? "SUCCESS" : "FAILED"));
                    client.pauseForUser();
                    break;

                case "2":
                    client.clearScreen();
                    System.out.println("FOLLOW USER");
                    System.out.println("Current time: " + client.timeFormat.format(client.logicalClock));
                    System.out.println("=".repeat(20));
                    System.out.print("Enter user ID to follow: ");
                    String targetUserId = scanner.nextLine();
                    JSONObject followResponse = client.followUser(targetUserId);
                    boolean followSuccess = followResponse.has("status")
                            && followResponse.getString("status").equals("success");
                    System.out.println(followSuccess ? "Now following user " + targetUserId
                            : "Failed to follow user " + targetUserId);
                    client.log("User action: Follow user " + targetUserId + " - "
                            + (followSuccess ? "SUCCESS" : "FAILED"));
                    client.pauseForUser();
                    break;

                case "3":
                    System.out.println("Loading notifications...");
                    client.log("User action: View notifications");
                    List<JSONObject> notificationPosts = client.getReceivedPosts();
                    client.displayPosts(notificationPosts);
                    break;

                case "4":
                    System.out.println("Loading all posts...");
                    client.log("User action: View all posts");
                    JSONObject allPostsResponse = client.getAllPosts();
                    if (allPostsResponse.has("status") && allPostsResponse.getString("status").equals("success")) {
                        if (allPostsResponse.has("posts")) {
                            JSONArray allPostsArray = allPostsResponse.getJSONArray("posts");
                            List<JSONObject> allPosts = new ArrayList<>();
                            for (int i = 0; i < allPostsArray.length(); i++) {
                                allPosts.add(allPostsArray.getJSONObject(i));
                            }
                            client.displayPosts(allPosts);
                        } else {
                            client.clearScreen();
                            System.out.println("No posts available");
                            client.pauseForUser();
                        }
                    } else {
                        client.clearScreen();
                        System.out.println("Failed to fetch posts: " +
                                allPostsResponse.optString("message", "Unknown error"));
                        client.pauseForUser();
                    }
                    break;

                case "5":
                    client.clearScreen();
                    System.out.println("SEND PRIVATE MESSAGE");
                    System.out.println("Current time: " + client.timeFormat.format(client.logicalClock));
                    System.out.println("=".repeat(30));
                    System.out.print("Enter receiver ID: ");
                    String receiverId = scanner.nextLine();
                    System.out.print("Enter message content: ");
                    String messageContent = scanner.nextLine();
                    JSONObject msgResponse = client.sendPrivateMessage(receiverId, messageContent);
                    boolean msgSuccess = msgResponse.has("status") && msgResponse.getString("status").equals("success");
                    System.out.println(msgSuccess ? "Message sent to user " + receiverId
                            : "Failed to send message: " + msgResponse.optString("message", "Unknown error"));
                    client.log("User action: Send private message to " + receiverId + " - "
                            + (msgSuccess ? "SUCCESS" : "FAILED"));
                    client.pauseForUser();
                    break;

                case "6":
                    client.log("User action: View private messages");
                    client.displayMessages();
                    break;

                case "8": // NOVA OPÇÃO - Adiantar relógio +1 segundo
                    client.clearScreen();
                    System.out.println("ADVANCE CLOCK");
                    System.out.println("Before: " + client.timeFormat.format(client.logicalClock));
                    client.adjustClock(1); // mudado para +1
                    System.out.println("After: " + client.timeFormat.format(client.logicalClock));
                    client.log("User action: Manual clock advance (+1 second)");
                    client.pauseForUser();
                    break;

                case "9": // NOVA OPÇÃO - Atrasar relógio -1 segundo
                    client.clearScreen();
                    System.out.println("DELAY CLOCK");
                    System.out.println("Before: " + client.timeFormat.format(client.logicalClock));
                    client.adjustClock(-1); // mudado para -1
                    System.out.println("After: " + client.timeFormat.format(client.logicalClock));
                    client.log("User action: Manual clock delay (-1 second)");
                    client.pauseForUser();
                    break;

                case "7":
                    client.clearScreen();
                    System.out.println("Flws!");
                    client.log("User action: Exit application");
                    running = false;
                    break;

                default:
                    client.clearScreen();
                    System.out.println("Invalid option. Please choose 1-9.");
                    client.log("User action: Invalid menu option (" + option + ")");
                    client.pauseForUser();
            }
        }

        client.close();
        scanner.close();
        System.out.println("Client terminated");
    }
}