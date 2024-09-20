import zmq
import json
import subprocess
import time
import signal
import sys

#TODO Implement functionality to get the data rate stats from the 3 subprocesses and write to a csv
#TODO Implement duration so that the replay stops after a while

host = "172.22.123.21"
port = "12345"
interface = "enp179s0f2"

# Hardcoded pcap paths
PCAPS = {
    "background": "/path/to/background.pcap",
    "traffic": "/path/to/traffic.pcap",
    "attack": "/path/to/attack.pcap"
}

# List to keep track of subprocesses
processes = []

# Function to start replaying a pcap file
def replay_pcap(pcap_path, data_rate):

    if data_rate != 0:
        #We assume that the string passed as argument is valid
        process = subprocess.Popen(["tcpreplay", f"-i {interface}", f"--mbps={data_rate}", pcap_path])
    else:
        process = subprocess.Popen(["tcpreplay", f"-i {interface}", pcap_path])
    
    processes.append(process)
    print(f"Replaying: {pcap_path}")

def terminate_program():
    # Terminate all subprocesses
    for proc in processes:
        proc.terminate()
        proc.wait()  # Wait for the process to exit
    # Clean up ZeroMQ
    socket.close()
    context.term()
    print("Program terminated")
    sys.exit(0)

# Signal handler for graceful shutdown
def signal_handler(sig, frame):
    print("\nInterrupt received, shutting down...")
    terminate_program()

# Setting up ZeroMQ server
context = zmq.Context()
socket = context.socket(zmq.REP)
socket.bind(f"tcp://{host}:{port}")  # Bind to TCP port 5555

# Register signal handler
signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)

print("Server is running and waiting for requests...")

while True:
    try:
        # Wait for JSON message
        message = socket.recv_json()
        print(f"Received message: {message}")

        state = message.get("state", 0)

        if state == "start":

            print("Starting pcap replay")
            # Extract the delay times from the received JSON
            try:
                #Delay in ms
                background_delay = message.get("background_delay", 0)
                traffic_delay = message.get("traffic_delay", 0)
                attack_delay = message.get("attack_delay", 0)

                data_rate = message.get("data_rate", 0)
                data_rate = data_rate.lower() == "true"

                background_data_rate = message.get("background_data_rate",0) if data_rate else 0
                traffic_data_rate = message.get("traffic_data_rate",0) if data_rate else 0
                attack_data_rate = message.get("attack_data_rate",0) if data_rate else 0

                # Replay the pcaps with the respective delays
                if background_delay > 0:
                    print(f"Waiting {background_delay} ms before replaying background...")
                    time.sleep(background_delay / 1000)  # Convert ms to seconds
                    replay_pcap(PCAPS["background"], background_data_rate)

                if traffic_delay > 0:
                    print(f"Waiting {traffic_delay} ms before replaying traffic...")
                    time.sleep(traffic_delay / 1000)  # Convert ms to seconds
                    replay_pcap(PCAPS["traffic"], traffic_data_rate)

                if attack_delay > 0:
                    print(f"Waiting {attack_delay} ms before replaying attack...")
                    time.sleep(attack_delay / 1000)  # Convert ms to seconds
                    replay_pcap(PCAPS["attack"], attack_data_rate)

                # Send a response back to the client
                socket.send_string("PCAPs replayed successfully")

            except KeyError as e:
                print(f"Invalid JSON structure: {e}")
                socket.send_string("Error: Invalid JSON structure")
        elif state == "stop":
            terminate_program()
        else:
            print("Unknown state")
    except zmq.Again as e:
        # No message received, continue to loop
        pass
    except Exception as e:
        # Handle unexpected exceptions
        print(f"Unexpected error: {e}")
        socket.send_string("Error: Unexpected error")
