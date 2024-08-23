package net.floodlightcontroller.resruleinstaller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class IpAddrGenerator {

    // Private constructor to prevent instantiation
    private IpAddrGenerator() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isPowerOfTwo(int n) {
        return (n > 0) && ((n & (n - 1)) == 0);
    }

    public static String intToIp(int ipInt) {
        try {
            byte[] bytes = ByteBuffer.allocate(4).putInt(ipInt).array();
            InetAddress inetAddress = InetAddress.getByAddress(bytes);
            return inetAddress.getHostAddress();
        } catch (UnknownHostException e) {
            // Handle the exception: return null, log the error, or throw a runtime exception
            System.err.println("Error converting int to IP: " + e.getMessage());
            return null; // or throw new RuntimeException("Error converting int to IP", e);
        }
    }

    public static int ipToInt(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            byte[] bytes = inetAddress.getAddress();
            return ByteBuffer.wrap(bytes).getInt();
        } catch (UnknownHostException e) {
            // Handle the exception: return a default value, log the error, or throw a runtime exception
            System.err.println("Error converting IP to int: " + e.getMessage());
            return -1; // or throw new RuntimeException("Error converting IP to int", e);
        }
    }

    public static int[] generateNumbersWithPrefix(int n) {
        if (n < 1 || n > 32) {
            throw new IllegalArgumentException("n must be between 1 and 32 (inclusive)");
        }

        int[] results = new int[(int) Math.pow(2, n)];

        for (int i = 0; i < results.length; i++) {
            int number = i << (32 - n);
            results[i] = number;
        }

        return results;
    }

    public static String[] generateIps(int n) {
        if (n < 1 || n > 32) {
            throw new IllegalArgumentException("n must be between 1 and 32 (inclusive)");
        }

        int[] ipNumbers = generateNumbersWithPrefix(n);
        String[] ipAddresses = new String[ipNumbers.length];

        for (int i = 0; i < ipNumbers.length; i++) {
            ipAddresses[i] = intToIp(ipNumbers[i]);
        }

        return ipAddresses;
    }

    public static String generateMask(int n) {
        if (n < 0 || n > 32) {
            throw new IllegalArgumentException("The parameter 'n' must be between 0 and 32 (inclusive)");
        }

        int mask = (1 << n) - 1;
        int fullMask = mask << (32 - n);

        return intToIp(fullMask);
    }

    public static void main(String[] args) {
        // Example usage
        int n = 24;
        String[] ips = generateIps(n);
        for (String ip : ips) {
            System.out.println(ip);
        }

        System.out.println("Mask: " + generateMask(n));
    }
}