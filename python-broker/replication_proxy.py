import zmq
import logging
import time

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("replication_proxy_log.txt"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("replication_proxy")

def main():
    logger.info("=== STARTING REPLICATION PROXY ===")
    
    # Inicializar contexto ZMQ
    context = zmq.Context()
    
    try:
        # Socket para receber replicações dos servidores (XSUB)
        # Servidores vão PUBLICAR aqui
        xsub_socket = context.socket(zmq.XSUB)
        xsub_socket.bind("tcp://*:6000")
        logger.info("XSUB socket bound to port 6000 (servers publish here)")
        
        # Socket para enviar replicações para os servidores (XPUB)  
        # Servidores vão ASSINAR aqui
        xpub_socket = context.socket(zmq.XPUB)
        xpub_socket.bind("tcp://*:6001")
        logger.info("XPUB socket bound to port 6001 (servers subscribe here)")
        
        # Aguardar um pouco para os sockets se estabilizarem
        time.sleep(1)
        
        logger.info("Replication proxy ready - starting message forwarding")
        logger.info("Servers should:")
        logger.info("   - PUBLISH replication data to port 6000")
        logger.info("   - SUBSCRIBE to replication data on port 6001")
        
        # Contadores para debugging
        messages_forwarded = 0
        subscriptions_handled = 0
        
        # Configurar poller para monitorar ambos os sockets
        poller = zmq.Poller()
        poller.register(xsub_socket, zmq.POLLIN)
        poller.register(xpub_socket, zmq.POLLIN)
        
        # Loop principal do proxy
        while True:
            try:
                # Poll com timeout de 1 segundo
                socks = dict(poller.poll(1000))
                
                # Mensagem vinda dos publishers (servidores enviando replicações)
                if xsub_socket in socks:
                    # Receber mensagem do XSUB e encaminhar para o XPUB
                    message = xsub_socket.recv_multipart(zmq.NOBLOCK)
                    xpub_socket.send_multipart(message, zmq.NOBLOCK)
                    
                    messages_forwarded += 1
                    
                    # Log detalhado da mensagem
                    if len(message) >= 2:
                        topic = message[0].decode('utf-8') if message[0] else 'no-topic'
                        try:
                            import json
                            data = json.loads(message[1].decode('utf-8'))
                            source_server = data.get('source_server', 'unknown')
                            replication_type = data.get('type', 'unknown')
                            logger.info(f"Forwarded replication #{messages_forwarded}: {replication_type} from Server {source_server}")
                        except:
                            logger.info(f"Forwarded message #{messages_forwarded}: {topic}")
                    else:
                        logger.info(f"Forwarded message #{messages_forwarded}")
                
                # Mensagem vinda dos subscribers (servidores se inscrevendo)
                if xpub_socket in socks:
                    # Receber mensagem do XPUB e encaminhar para o XSUB
                    message = xpub_socket.recv_multipart(zmq.NOBLOCK)
                    xsub_socket.send_multipart(message, zmq.NOBLOCK)
                    
                    subscriptions_handled += 1
                    
                    # Log da inscrição
                    if message and len(message[0]) > 0:
                        subscription_info = message[0]
                        if subscription_info[0:1] == b'\x01':  # Subscription
                            topic = subscription_info[1:].decode('utf-8')
                            logger.info(f"New subscription #{subscriptions_handled}: '{topic}'")
                        elif subscription_info[0:1] == b'\x00':  # Unsubscription
                            topic = subscription_info[1:].decode('utf-8')
                            logger.info(f"Unsubscription #{subscriptions_handled}: '{topic}'")
                    
            except zmq.Again:
                # Timeout - não há mensagens, continuar
                continue
            except Exception as e:
                logger.error(f"Error in proxy loop: {e}")
                continue
                
    except KeyboardInterrupt:
        logger.info("Replication proxy terminated by user")
    except Exception as e:
        logger.error(f"Replication proxy error: {e}")
    finally:
        try:
            xsub_socket.close()
            xpub_socket.close()
            context.term()
            logger.info("Replication proxy cleanup completed")
        except:
            pass

if __name__ == "__main__":
    main()