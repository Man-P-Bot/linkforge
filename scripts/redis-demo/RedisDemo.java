import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * LinkForge 第 0 周 Redis 入门 Demo —— 不引入 Spring Boot，不引入任何第三方依赖。
 *
 * 覆盖四件事：
 *   1. SET    写入字符串
 *   2. GET    读回字符串
 *   3. EXPIRE 设置 TTL，并实测「到期后自动消失」
 *   4. INCR   自增，并验证其原子语义
 *
 * 实现方式：直接用一个最小 RESP（REdis Serialization Protocol）客户端连接 Redis，
 * 目的是看清「一次 Redis 命令在网络上的样子」，而不是被框架包装掉。
 *
 * 用法（JDK 11+ 可直接运行单文件源码）：
 *   java scripts/redis-demo/RedisDemo.java
 *   java scripts/redis-demo/RedisDemo.java localhost 6379
 *   java scripts/redis-demo/RedisDemo.java localhost 6379 --keep
 *
 * 参数：
 *   args[0]  host，默认 localhost
 *   args[1]  port，默认 6379
 *   args[2]  传入 --keep 时保留演示用的 key（默认会清理）
 */
public final class RedisDemo {

    private static final String USAGE = "用法: java RedisDemo.java [host] [port] [--keep]";

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 6379;
        boolean keepKeys = false;

        for (String arg : args) {
            if ("--keep".equals(arg)) {
                keepKeys = true;
            } else if (arg.chars().allMatch(Character::isDigit) && !arg.isEmpty()) {
                port = Integer.parseInt(arg);
            } else if (!arg.startsWith("-")) {
                host = arg;
            } else {
                System.err.println("未知参数: " + arg);
                System.err.println(USAGE);
                System.exit(2);
            }
        }

        String suffix = Long.toString(System.currentTimeMillis());
        String stringKey = "linkforge:demo:string:" + suffix;
        String ttlKey = "linkforge:demo:ttl:" + suffix;
        String counterKey = "linkforge:demo:counter:" + suffix;

        System.out.println("== LinkForge Redis Demo ==");
        System.out.println("目标: " + host + ":" + port);
        System.out.println("key 前缀: linkforge:demo:*:" + suffix);
        System.out.println();

        int failed = 0;

        try (MiniRedis redis = new MiniRedis(host, port)) {
            System.out.println("-- 0. 连通性 --");
            String pong = redis.command("PING");
            System.out.println("  PING -> " + pong);
            if (!"PONG".equals(pong)) {
                System.err.println("  连接失败：Redis 未返回 PONG");
                System.exit(1);
            }
            System.out.println();

            System.out.println("-- 1. SET / GET --");
            String value = "https://example.com/very/long/path";
            System.out.println("  SET " + stringKey);
            System.out.println("      -> " + redis.command("SET", stringKey, value));
            String got = redis.command("GET", stringKey);
            System.out.println("  GET " + stringKey);
            System.out.println("      -> " + got);
            failed += check("GET 读回值与写入值一致", value.equals(got));
            System.out.println();

            System.out.println("-- 2. EXPIRE + TTL --");
            System.out.println("  SET " + ttlKey + " \"will-expire\"");
            System.out.println("      -> " + redis.command("SET", ttlKey, "will-expire"));
            System.out.println("  EXPIRE " + ttlKey + " 2");
            System.out.println("      -> " + redis.command("EXPIRE", ttlKey, "2"));
            long ttl = redis.commandAsLong("TTL", ttlKey);
            System.out.println("  TTL  " + ttlKey);
            System.out.println("      -> " + ttl + " 秒");
            failed += check("TTL 落在 1~2 秒", ttl >= 1 && ttl <= 2);

            System.out.print("  等待 3 秒让 key 到期");
            for (int i = 0; i < 3; i++) {
                Thread.sleep(1000);
                System.out.print(".");
            }
            System.out.println();
            String expired = redis.command("GET", ttlKey);
            System.out.println("  GET " + ttlKey + " （到期后）");
            System.out.println("      -> " + expired);
            failed += check("到期后 GET 返回 nil", expired == null);
            System.out.println();

            System.out.println("-- 3. INCR --");
            System.out.println("  INCR " + counterKey);
            System.out.println("      -> " + redis.command("INCR", counterKey));
            System.out.println("  INCR " + counterKey);
            System.out.println("      -> " + redis.command("INCR", counterKey));
            long counter = redis.commandAsLong("GET", counterKey);
            System.out.println("  GET  " + counterKey);
            System.out.println("      -> " + counter);
            failed += check("INCR 两次后计数为 2（原子自增）", counter == 2);
            System.out.println();

            if (keepKeys) {
                System.out.println("-- 清理 -- 传入 --keep，保留 key 供手动观察");
                System.out.println("  redis-cli GET " + stringKey);
            } else {
                long removed = redis.commandAsLong("DEL", stringKey, counterKey);
                System.out.println("-- 清理 -- DEL 删除 " + removed + " 个 key（到期的 key 已自动消失）");
            }
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("结果: 全部通过 — SET / GET / EXPIRE / INCR 四项均符合预期");
        } else {
            System.out.println("结果: " + failed + " 项不符合预期");
            System.exit(1);
        }
    }

    private static int check(String label, boolean ok) {
        System.out.println("  [" + (ok ? "通过" : "失败") + "] " + label);
        return ok ? 0 : 1;
    }

    /** 最小 RESP 客户端：只实现演示需要的命令与回复类型。 */
    private static final class MiniRedis implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        MiniRedis(String host, int port) throws IOException {
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress(host, port), 3000);
            this.socket.setSoTimeout(5000);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        String command(String... args) throws IOException {
            writeCommand(args);
            return readReply();
        }

        long commandAsLong(String... args) throws IOException {
            String reply = command(args);
            if (reply == null) {
                throw new IOException("期望整数回复，实际为 nil");
            }
            return Long.parseLong(reply);
        }

        private void writeCommand(String... args) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append('*').append(args.length).append("\r\n");
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private String readReply() throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new IOException("连接被对端关闭");
            }
            String line = readLine();
            switch (type) {
                case '+':
                    return line;
                case '-':
                    throw new IOException("Redis 返回错误: " + line);
                case ':':
                    return line;
                case '$':
                    int length = Integer.parseInt(line);
                    if (length == -1) {
                        return null;
                    }
                    byte[] payload = new byte[length];
                    int read = 0;
                    while (read < length) {
                        int n = in.read(payload, read, length - read);
                        if (n == -1) {
                            throw new IOException("读取批量字符串时连接中断");
                        }
                        read += n;
                    }
                    in.read();
                    in.read();
                    return new String(payload, StandardCharsets.UTF_8);
                default:
                    throw new IOException("未支持的回复类型: " + (char) type);
            }
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') {
                    int next = in.read();
                    if (next == '\n') {
                        break;
                    }
                    sb.append((char) b).append((char) next);
                } else {
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
