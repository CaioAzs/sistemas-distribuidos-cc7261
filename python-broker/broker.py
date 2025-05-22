import zmq
import time
import logging

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("broker_log.txt"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("broker")

def main():
    logger.info("Starting simple broker")
    
    # Inicializar contexto ZMQ
    context = zmq.Context()
    
    # Socket para clientes (ROUTER)
    frontend = context.socket(zmq.ROUTER)
    frontend.bind("tcp://*:5555")
    logger.info("Frontend bound to port 5555")
    
    # Socket para servidores (DEALER)
    backend = context.socket(zmq.DEALER)
    backend.bind("tcp://*:5556")
    logger.info("Backend bound to port 5556")
    
    # Configurar poller
    poller = zmq.Poller()
    poller.register(frontend, zmq.POLLIN)
    poller.register(backend, zmq.POLLIN)
    
    # Contadores para estatísticas
    client_count = 0
    server_count = 0
    
    logger.info("Broker ready for message routing (3 servers expected)")
    
    # Loop principal
    while True:
        try:
            # Aguardar eventos nos sockets
            socks = dict(poller.poll(1000))  # timeout de 1 segundo
            
            # Mensagem do cliente para o servidor
            if frontend in socks and socks[frontend] == zmq.POLLIN:
                client_count += 1
                message = frontend.recv_multipart()
                backend.send_multipart(message)
                logger.info(f"Forwarded client message #{client_count}")
            
            # Mensagem do servidor para o cliente
            if backend in socks and socks[backend] == zmq.POLLIN:
                server_count += 1
                message = backend.recv_multipart()
                frontend.send_multipart(message)
                logger.info(f"Forwarded server message #{server_count}")
            
        except Exception as e:
            logger.error(f"Error in message routing: {e}")
        
        # Pequena pausa para evitar consumo excessivo de CPU
        time.sleep(0.001)

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        logger.info("Broker terminated by user")
    except Exception as e:
        logger.error(f"Broker terminated with error: {e}")