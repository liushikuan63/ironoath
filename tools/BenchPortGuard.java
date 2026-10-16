import java.net.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Finite preflight/negative control in the new Java container; never opens the original app port. */
public final class BenchPortGuard {
    static void allowed(int port) {
        if(port<1024 || port>65535 || port==9490 || port==27017)
            throw new IllegalArgumentException("Protected or invalid benchmark port");
    }
    static boolean free(int port) throws java.io.IOException {
        allowed(port);
        try(ServerSocket socket=new ServerSocket()) {
            socket.setReuseAddress(false);socket.bind(new InetSocketAddress("0.0.0.0",port));return true;
        }catch(BindException busy) { return false; }
    }
    static void requireFree(int port) throws java.io.IOException {
        if(!free(port))throw new IllegalStateException("Benchmark port is already bound; preserve its owner");
    }
    public static void main(String[] args)throws Exception {
        if(!"17".equals(System.getProperty("java.specification.version")) || !"Linux".equals(System.getProperty("os.name")))
            throw new IllegalStateException("Linux JRE17 required");
        int target=Integer.parseInt(args[1]);requireFree(target);
        int busyPort;boolean rejected=false,protectedRejected=false,listenerPreserved;
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            busyPort=listener.getLocalPort();
            try { requireFree(busyPort); } catch(IllegalStateException expected) { rejected=true; }
            listenerPreserved=!listener.isClosed();
        }
        try { allowed(9490); } catch(IllegalArgumentException expected) { protectedRejected=true; }
        if(!rejected || !listenerPreserved || !free(busyPort) || !protectedRejected)
            throw new AssertionError("Port guard negative control failed");
        requireFree(target);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),
            Map.of("targetPort",target,"targetFree",true,"busyNegativeRejected",true,
                "busyListenerPreserved",true,"ephemeralReleased",true,"protected9490RejectedWithoutBinding",true,
                "javaVersion",System.getProperty("java.runtime.version"),"osName",System.getProperty("os.name")));
    }
}
