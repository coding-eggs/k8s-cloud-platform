package com.coding.common.utils;

import java.security.SecureRandom;
import java.time.Instant;

public class ULIDGenerator {

    private static final SecureRandom random = new SecureRandom();

    public static String generateULID () {
        long timestamp = Instant.now().toEpochMilli();
        byte [] randomBytes = new byte[10];
        random.nextBytes(randomBytes);
        long ulid = (timestamp << 16) | byteArrayToLong(randomBytes);
        return Long.toHexString(ulid);
    }

    private static long byteArrayToLong (byte [] bytes) {
        long result = 0;
        for (byte aByte : bytes) {
            result = (result << 8) | (aByte & 0xFF);
        }
        return result;
    }


    public static void main(String[] args) {
        System.out.println(generateULID());
    }

}
