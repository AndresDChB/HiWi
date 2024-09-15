import zmq
import time
import sys
import numpy as np
import json

context = zmq.Context()
socket = context.socket(zmq.REP)
socket.bind("tcp://localhost:5555")

for _ in range(103):
    message = socket.recv_string()
    print(f"Received message: {message}")
    socket.send_string("Response")