package com.lh.cache.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @Author haol
 * @Date 20-11-23 10:00
 * @Version 1.0
 * @Desciption
 */
public class NetworkUtil {

    /**
     * 获取本机ip
     * @return
     */
    public static String getLocalId(){
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return null;
    }
}
