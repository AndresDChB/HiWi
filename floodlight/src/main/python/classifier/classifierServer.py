import zmq
import threading
import time
import select
import signal
import sys
import numpy as np
import json
import classifier as cl

# Event to signal threads to stop
stop_event = threading.Event()

# Signal handler for CTRL+C
def signal_handler(sig, frame):
    print("\nCtrl+C detected! Stopping all threads...")
    stop_event.set()  # Signal all threads to stop

# Register signal handler
signal.signal(signal.SIGINT, signal_handler)

def run():
    threads = []
    mng_inputs_thread = threading.Thread(target=manage_inputs)
    communication_thread = threading.Thread(target=communicate_with_java)

    #Deamon just in case
    mng_inputs_thread.daemon = True
    communication_thread.daemon = True

    threads.append(mng_inputs_thread)
    threads.append(communication_thread)

    for thread in threads:
        thread.start()
    
    for thread in threads:
        thread.join()


def manage_inputs():
    prompt_shown = False
    while not stop_event.is_set():
        if not prompt_shown:
            print("Enter command: ", end="", flush=True)
            prompt_shown = True
        
        # Wait for input with a 1-second timeout
        if select.select([sys.stdin], [], [], 1)[0]:
            command = sys.stdin.readline().strip()
            if command == "exit":
                print("\nExit command received! Stopping all threads...")
                stop_event.set()
                break
            else:
                print(f"\nUnknown command: {command}")
            # Reset prompt state to show again for next input
            prompt_shown = False

def communicate_with_java():
    classifier = cl.Classifier(16,1,False)
    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.bind("tcp://localhost:5555")

    poller = zmq.Poller()
    poller.register(socket, zmq.POLLIN)
    while not stop_event.is_set():
        
        socks = dict(poller.poll(1000))

        if socket in socks and socks[socket] == zmq.POLLIN:
            try :
                receive_time_ns = time.time_ns()

                message = socket.recv_string()
                print(f"Received Aggregation Map")
                json_data = json.loads(message)
                agg_map = json_data["aggMap"]


                timestamp = json_data["timestamp"]

                print(f"Python timestamp {receive_time_ns}")
                print(f"Java timestamp {timestamp}")

                sendTime = receive_time_ns - timestamp

                print(f"Sending latency: {sendTime}")
                np_agg_map = np.array(agg_map)
                #print(np_agg_map)
                classifier.classify(np_agg_map,0)
            
            except Exception as e:
                print(f"Something went wrong \n {e}")

            

            socket.send_string("Hello, Client!")
    socket.close()
    context.term()
        
run()