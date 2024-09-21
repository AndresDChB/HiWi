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
    "background": "/home/borja/pcaps/mawi_background.pcap",
    "traffic": "/home/borja/pcaps/mawi_background.pcap",
    "attack": "/home/borja/pcaps/mawi_background.pcap"
}

# List to keep track of subprocesses
processes = []

# Function to start replaying a pcap file
def replay_pcap(pcap_path, data_rate):

    if data_rate != 0:
        #We assume that the string passed as argument is valid
        process = subprocess.Popen(["tcpreplay",f"--intf1={interface}",f"--mbps={data_rate}",pcap_path],
        stdout=subprocess.PIPE,  # Capture stdout
        stderr=subprocess.PIPE  # Capture stderr
        )
    else:
        process = subprocess.Popen(["tcpreplay", f"-i {interface}", pcap_path],
        stdout=subprocess.PIPE,  # Capture stdout
        stderr=subprocess.PIPE  # Capture stderr
        )
    
    processes.append(process)
    print(f"Replaying: {pcap_path}")

def terminate_program():
    # Terminate all subprocesses
    for proc in processes:
        proc.terminate()
        try:
            proc.wait(timeout=5)  # Wait for process to terminate
        except subprocess.TimeoutExpired:
            proc.kill()  # Force kill if it doesn't terminate within the timeout
        # Capture output after termination
        stdout, stderr = proc.communicate()
        print(f"Output from {proc.args}:")
        print(stdout.decode())
        if stderr:
            print("Errors:")
            print(stderr.decode())
    socket.close()
    context.term()
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
        message = socket.recv_string()
        print(f"Received message: {message}")
        if not message:
            continue
        json_obj = json.loads(message)

        state = json_obj.get("state", 0)

        if state == "start":

            print("Starting pcap replay")
            # Extract the delay times from the received JSON
            try:
                #Delay in ms
                background_delay = json_obj.get("background_delay", 0)
                traffic_delay = json_obj.get("traffic_delay", 0)
                attack_delay = json_obj.get("attack_delay", 0)


                background_data_rate = json_obj.get("background_data_rate",0) 
                traffic_data_rate = json_obj.get("traffic_data_rate",0) 
                attack_data_rate = json_obj.get("attack_data_rate",0) 

                print(f"JSON values: {background_delay}, {traffic_delay}, {attack_delay}, {background_data_rate}, {traffic_data_rate}, {attack_data_rate}")

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
