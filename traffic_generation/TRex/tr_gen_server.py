import sys
import math
import csv_writer
sys.path.append('/home/borja/miniconda3/envs/py39/lib/python3.9/site-packages')
import zmq as zmq_local
sys.path.append('/opt/trex/v3.04/automation/trex_control_plane/interactive/')
sys.path.append('/home/borja/code/ryu/controllers/help_programs')
sys.path.insert(0, "/opt/trex/v3.04/external_libs/pyzmq-ctypes")


from Classification.ip_addr_generator import *
from trex_stl_lib.api import *
import threading, json, time

# Initialize TRex client
client = STLClient()
initial_stats = None
start_time = None

def traffic_generation(data_rate, resolution, duration = 0):
    global client
    global initial_stats
    global start_time
    try:

        client.connect()
        # Reset the ports
        client.reset(ports=[0, 1])
        client.set_port_attr(ports=[0, 1], promiscuous=True)
        #Having too many streams reduces the performance
        if resolution < 64:
            bits = int(math.log(resolution,2))
        else:
            bits = int(math.log(32,2)) 
        ips = generate_ips(bits)
        # List to hold all the streams
        streams = []

        # Generate streams for every src and dst IP permutation 
        for src_ip in ips:
            for dst_ip in ips:
                base_pkt = Ether(dst = '34:ef:b6:9b:ba:e6')/IP(src=src_ip, dst=dst_ip)/UDP()/("X" * 1000)  # Example packet
                pad = max(0, 60 - len(base_pkt)) * 'x'
                stream = STLStream(packet=STLPktBuilder(pkt=base_pkt/pad),
                                mode=STLTXCont())
                streams.append(stream)


        # Add streams to port 0
        client.add_streams(streams, ports=[0])

        initial_stats = client.get_stats()
        start_time = time.time()
        # Start the traffic
        print('Starting traffic')
        if duration > 0:
            print(f'Measuring data rate for: {duration} seconds')
            client.start(ports=[0], mult=f"{data_rate}mbps", duration = duration)  
        elif duration == 0:
            print("Measuring data rate")
            client.start(ports=[0], mult=f"{data_rate}mbps")  
        else:
            client.disconnect()
            print('Invalid duration value')
            exit()

        print('Waiting...')
        client.wait_on_traffic(ports=[0])
        print('Waiting ended')
    except STLError as e:
        print(e)

    finally:
        client.disconnect()

def stop_traffic_generation(resolution, write):
    global client
    global initial_stats
    global start_time
    try:

        # Stop the traffic
        client.stop(ports=[0])
        print("Traffic stopped.")
        final_stats = client.get_stats()
        stop_time = time.time()
        test_duration = stop_time - start_time
        tx_bytes = final_stats[0]['obytes'] - initial_stats[0]['obytes']
        rx_bytes = final_stats[1]['ibytes'] - initial_stats[1]['ibytes']
        avg_tx_data_rate = (tx_bytes * 8) / test_duration  # bits per second
        avg_rx_data_rate = (rx_bytes * 8) / test_duration  # bits per second
        
        print(f"Average TX Data Rate of Port 0: {avg_tx_data_rate} bps")
        print(f"Average RX Data Rate of Port 1: {avg_rx_data_rate} bps")
        print(resolution)
        csv_writer.write('data_rate_avg/rcv_data_rate.csv',[[resolution, avg_rx_data_rate, test_duration]], write)
        csv_writer.write('data_rate_avg/tr_data_rate.csv',[[resolution, avg_tx_data_rate, test_duration]], write)
    except TRexError as e:
        print(f"Error when stopping traffic. Error: {e}")
    finally:
        client.disconnect()

def measure_data_rate(resolution, iterations, write):
    if not write:
        return
    #Give time to data rate to stabilize
    time.sleep(3)
    waiting_time = 1
    for _ in range(iterations):
        
        initial_stats = client.get_stats()
        #measure real waiting time for better precision
        initial_time = time.time()

        time.sleep(waiting_time)

        final_stats = client.get_stats()
        end_time = time.time()

        real_duration = end_time - initial_time
        tx_bytes = final_stats[0]['obytes'] - initial_stats[0]['obytes']
        rx_bytes = final_stats[1]['ibytes'] - initial_stats[1]['ibytes']
        avg_tx_data_rate = (tx_bytes * 8) / real_duration  # bits per second
        avg_rx_data_rate = (rx_bytes * 8) / real_duration  # bits per second
    
        print(f"Average TX Data Rate of Port 0: {avg_tx_data_rate} bps")
        print(f"Average RX Data Rate of Port 1: {avg_rx_data_rate} bps")
        csv_writer.write('data_rate_intervals/rx_traffic_monitoring.csv',[[resolution, avg_rx_data_rate, real_duration]], write)
        csv_writer.write('data_rate_intervals/tx_traffic_monitoring.csv',[[resolution, avg_tx_data_rate, real_duration]], write)
    
    print('Interval data rate measurements done')

def server_program():
    global client
    host = "172.22.123.21"
    port = "12345"
    traffic_thread = None
    context = None
    server_socket = None

    try:
        context = zmq_local.Context()
        server_socket = context.socket(zmq_local.REP)
        server_socket.bind(f"tcp://{host}:{port}")
        print(f"Server listening on {host}:{port}")
        

        while True:
            data = server_socket.recv_string()
            server_socket.send_string("Message received")
            json_obj = json.loads(data)
            if not data:
                break
            print("Received:", data)
            state = json_obj.get('state')

            resolution = json_obj.get('resolution')
            resolution = int(resolution)
            #As we are drilling down the flow rules wont match all packets 
            #and the deeper the drilldown, the higher the be packet loss will be. 
            #Only Tx data rate matters to ensure that the experiment had the right data rate
            write = json_obj.get('write')
            write = write.lower() == 'true'

            data_rate = json_obj.get('data_rate')

            duration = int(json_obj.get('duration'))

            iterations = json_obj.get('iterations')
            iterations = float(iterations)

            iterations *= 32 / math.log2(resolution)

            iterations = int(iterations)

            print(f"State: {state}, resolution: {resolution}, write: {write}, data_rate: {data_rate}Mbps, iterations: {iterations}")

            if state == 'start':

                if traffic_thread is not None:
                    print("Traffic generation is already running. Stop it first.")
                    continue

                traffic_thread = threading.Thread(target=traffic_generation, 
                                                  args=(data_rate, resolution, duration), daemon=True)
                measurement_thread = threading.Thread(target=measure_data_rate, args=(resolution, iterations, write), daemon=True)
                traffic_thread.start()
                measurement_thread.start()
                
            elif state == 'stop':
                # Final stats after traffic ends

                if traffic_thread is None:
                    print("Traffic generation is not running.")
                    continue
                stop_traffic_generation(resolution, write)
                traffic_thread = None              

    except KeyboardInterrupt:
        print("\nKeyboard interrupt received, exiting.")
    except Exception as e:
        print(f"Something went wrong {e}")
    finally:
        if traffic_thread is not None:
            stop_traffic_generation(resolution, write)
            traffic_thread.join()
        
        #Maybe close conn too/instead
        if server_socket is not None:
            server_socket.close()
        if context is not None:
            context.term()
        print("Server socket closed.")

if __name__ == "__main__":

    server_program()