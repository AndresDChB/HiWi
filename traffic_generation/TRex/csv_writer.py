import csv
import os

def is_csv_file(path):
    #print(f"Path: {path}")
    # Check if the path exists
    if os.path.exists(path):
        # Check if the path points to a file
        #print("Path exists")
        if os.path.isfile(path):
            #print("File found at path")
            # Get the file extension
            _, extension = os.path.splitext(path)
            #print("CSV file found")
            # Check if the file extension is '.csv'
            if extension.lower() == '.csv':
                return True
    return False

def write(file, data, write):

    if not write:
        print("CSVWriter is turned off")
        return

    directory = '/home/borja/t-rex/Measurements/9_8G'  
    file_path = os.path.join(directory, file)

    if not is_csv_file(file_path):
        print("Invalid file")
        return

    with open(file_path, mode = 'a', newline= '') as open_file:
        writer = csv.writer(open_file)
        for row in data:
            writer.writerow(row)

    print(f"Added new data to '{file_path}'")        
    

