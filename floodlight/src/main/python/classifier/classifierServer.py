import zmq
import threading
import time
import select
import signal
import sys
import numpy as np
import json
import classifier as cl
sys.path.append('/home/borja/HiWi/floodlight/src/main/python')
import csv_writer

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
    #TODO add resolution in the config file/CLI
    res = 16
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

                message = socket.recv_string()
                print(f"Received Aggregation Map")
                json_data = json.loads(message)
                agg_map = json_data["aggMap"]

                write = json_data['write']
                write = write.lower() == 'true'

                np_agg_map = np.array(agg_map)
                #print(np_agg_map)

                prediction_start_time = time.time()
                prediction  = classifier.classify(np_agg_map,0)
                prediction_end_time = time.time()

                classification_time = prediction_end_time - prediction_start_time

                print(f"Classification took {classification_time} seconds")
                print(prediction)


                csv_writer.write("classification_latency.csv",[[res, classification_time]], write)

            
            except Exception as e:
                print(f"Something went wrong \n {e}")

            

            socket.send_string(np.array2string(prediction))
    socket.close()
    context.term()
        
run()