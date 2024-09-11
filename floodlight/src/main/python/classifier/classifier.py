import sys
import os

import time
import numpy as np
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import cnn_lstm as cnn
from collections import deque

class Classifier():

    def __init__(self, res, queue_size, write):
        self.res = res
        self.cnn = cnn.getModel(resolution=self.res)
        self.write = write
        self.queue_size = queue_size
        self.img_q = deque(maxlen=queue_size)
        

    def classify(self, np_aggregation, stats_req_time):

        #start_time = time.time()

        #request_classifier_lat = start_time - stats_req_time
        #csv_writer.write('req_class_start.csv', [[self.res, request_classifier_lat]], self.write)

        if np_aggregation is None:
            print("Classification failed: No aggregation found")
            return

        cnn_input = np.zeros((1, self.queue_size, self.res, self.res, 1))
        np_aggregation = np.expand_dims(np_aggregation, axis=-1)

        if (len(self.img_q) >= self.queue_size - 1):

            self.img_q.append(np_aggregation)

            img_list = np.array(list(self.img_q))

            cnn_input[0] = img_list

        else:
            self.img_q.append(np_aggregation)
            print("Queue is not full, waiting for more images")
            return

        #start_c_time = time.time()  

        prediction = self.cnn.predict(cnn_input)

        #class_duration = time.time() - start_c_time
        #print(f"Classfication returned after {class_duration} seconds")
        #csv_writer.write('classification.csv', [[self.res, class_duration]], self.write)

        #req_to_class_duration = time.time() - stats_req_time
        #print(f"Total time from request to classification: {req_to_class_duration} seconds")
        #csv_writer.write('class_from_req.csv', [[self.res, req_to_class_duration]], self.write)
        print(prediction)

if __name__ == "__main__":
    """ import sys
    classifier = Classifier()
    np_aggregation = int(sys.argv[1])
    stats_req_time = int(sys.argv[2])
    classifier.classify(np_aggregation, stats_req_time) """
    print("Connection worked")

                
