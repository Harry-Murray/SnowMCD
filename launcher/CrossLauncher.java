package com.snowmcd;

/**
 * Small launcher compiled with {@code --release 17} so that older JVMs can still
 * start and print a useful message instead of raising UnsupportedClassVersionError.
 */
public final class CrossLauncher {

    private CrossLauncher() {
    }

    public static void main(String[] args) {
        if (Runtime.version().feature() < 25) {
            System.err.println("SnowMCD 需要 Java 25 或更高版本，当前为 " + Runtime.version() + "。");
            System.err.println("请安装 JDK 25 后重新执行: java -jar SnowMCD-cross.jar");
            System.exit(1);
        }
        try {
            Class.forName("com.snowmcd.Bootstrap")
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("SnowMCD 启动失败: " + cause);
            cause.printStackTrace();
            System.exit(1);
        }
    }
}
