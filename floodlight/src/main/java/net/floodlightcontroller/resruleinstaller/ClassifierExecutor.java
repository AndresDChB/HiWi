package net.floodlightcontroller.resruleinstaller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

public class ClassifierExecutor {

    public ClassifierExecutor() {   

    }
    
    
    protected void executeClassification(long[][] aggMap, long reqTimeNano) {
        System.out.println("[ClassifierExecutor] Trying to call python");
        try {
            String pythonPath = "/home/borja/miniconda3/envs/py39/bin/python3";
            String pyClassifierPath = "/home/borja/HiWi/floodlight/src/main/python/classifier/classifier.py";
            String[] command = new String[]{pythonPath, pyClassifierPath};
            // Use ProcessBuilder to run the command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true); // Combine error stream with output stream
            Process process = processBuilder.start();

            // Read the output from the Python script
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line); // Print the output from the Python script
            }

            // Wait for the process to finish
            process.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
