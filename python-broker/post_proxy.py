import zmq
import logging
import time

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("post_proxy_log.txt"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("post_proxy")

def main():
    logger.info("=== STARTING POST PUBLICATION PROXY ===")
    
    # Inicializar contexto ZMQ
    context = zmq.Context()
    
    try:
        # Socket para receber posts dos servidores (XSUB)
        # Servidores vão PUBLICAR posts aqui
        xsub_socket = context.socket(zmq.XSUB)
        xsub_socket.bind("tcp://*:5557")
        logger.info("XSUB socket bound to port 5557 (servers publish posts here)")
        
        # Socket para enviar posts para os clientes (XPUB)
        # Clientes vão ASSINAR posts aqui  
        xpub_socket = context.socket(zmq.XPUB)
        xpub_socket.bind("tcp://*:5558")
        logger.info("XPUB socket bound to port 5558 (clients subscribe here)")
        
        # Aguardar um pouco para os sockets se estabilizarem
        time.sleep(1)
        
        logger.info("Post proxy ready - starting post forwarding")
        logger.info("Architecture:")
        logger.info("   - Servers PUBLISH posts to port 5557")
        logger.info("   - Clients SUBSCRIBE to posts on port 5558")
        
        # Contadores para debugging
        posts_forwarded = 0
        subscriptions_handled = 0
        
        # Configurar poller
        poller = zmq.Poller()
        poller.register(xsub_socket, zmq.POLLIN)
        poller.register(xpub_socket, zmq.POLLIN)
        
        # Loop principal do proxy
        while True:
            try:
                socks = dict(poller.poll(1000))
                
                # Posts vindo dos servidores
                if xsub_socket in socks:
                    message = xsub_socket.recv_multipart(zmq.NOBLOCK)
                    xpub_socket.send_multipart(message, zmq.NOBLOCK)
                    
                    posts_forwarded += 1
                    
                    # Log do post
                    if message:
                        post_data = message[0].decode('utf-8')
                        if ':' in post_data:
                            user_id = post_data.split(':', 1)[0]
                            logger.info(f"Forwarded post #{posts_forwarded} from user {user_id}")
                        else:
                            logger.info(f"Forwarded post #{posts_forwarded}")
                
                # Inscrições dos clientes
                if xpub_socket in socks:
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
                continue
            except Exception as e:
                logger.error(f"Error in post proxy loop: {e}")
                continue
                
    except KeyboardInterrupt:
        logger.info("Post proxy terminated by user")
    except Exception as e:
        logger.error(f"Post proxy error: {e}")
    finally:
        try:
            xsub_socket.close()
            xpub_socket.close()
            context.term()
            logger.info("Post proxy cleanup completed")
        except:
            pass

if __name__ == "__main__":
    main()