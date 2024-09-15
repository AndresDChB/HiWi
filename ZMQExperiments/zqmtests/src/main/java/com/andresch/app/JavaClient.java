package com.andresch.app;
import org.zeromq.ZMQ;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import csvwriter.CSVWriter;
import java.util.Random;
import java.io.IOException;
import csvwriter.CSVWriter;
public class JavaClient {

    static ZMQ.Context socketContext = ZMQ.context(1);
    static ZMQ.Socket socket = socketContext.socket(ZMQ.REQ);
    
    public JavaClient() {

    }

    private static int[][] aggMapMock(int res) {

        int[][] result = new int[res][res];
        Random random = new Random();
        for (int i = 0; i < res; i++) {
            for (int j = 0; j < res; j++) { 
                result[i][j] = random.nextInt(Integer.MAX_VALUE - 1);
            }
        }
        return result;
    }

    private static JSONArray jsonAggMapMock(int[][] aggMapMock) {
        JSONArray aggMapJSON = new JSONArray();
            for (int[] row : aggMapMock) {
                JSONArray rowArray = new JSONArray();
                for (int value : row) {
                    rowArray.put(value);
                }
                aggMapJSON.put(rowArray);
            }
        return aggMapJSON;
    }

    public static void main(String[] args) {
        int res = 16;
        int measurements = 103;
        boolean write = true;
        socket.connect("tcp://localhost:5555");
        JSONObject jsonMessage = new JSONObject();
        int[][] aggMapMock = aggMapMock(res);
        JSONArray jsonAggMapMock = jsonAggMapMock(aggMapMock);
        try {
            jsonMessage.put("Message", jsonAggMapMock);
        } catch (JSONException e) {
            System.out.println(e);
        }
        

        String jsonString = jsonMessage.toString();

        for (int i = 0; i < measurements; i++) {
            long sendingTimeNano = System.nanoTime();
            socket.send(jsonString.getBytes(ZMQ.CHARSET), 0);
            String message = socket.recvStr(0);
            System.out.println("Message received: " + message);
            long recvTimeNano = System.nanoTime();
            double rttSeconds = (double) (recvTimeNano - sendingTimeNano) / 1000000000;
            System.out.println("RTT: " + rttSeconds);


            String[] resAndRTT = new String[]{String.valueOf(res), String.valueOf(rttSeconds)};
            String newData = CSVWriter.convertToCSV(resAndRTT);
            try {
                CSVWriter.writeToCsv("/home/borja/HiWi/ZMQExperiments/zqmtests/results/rtt.csv", newData, write);
            }
            catch (IOException e) {
                System.out.println("CSV Writer fn exploded lfmao\n" + e);
            }
        }
        
    }

    
}
