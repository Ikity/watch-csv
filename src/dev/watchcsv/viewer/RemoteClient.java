package dev.watchcsv.viewer;

import android.content.Context;
import com.google.android.gms.tasks.*;
import com.google.android.gms.wearable.*;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One short request per channel. Long-running watch jobs are polled by receipt ID. */
public final class RemoteClient implements Closeable {
    private final ChannelClient client;
    private final CapabilityClient capabilities;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private static final class Exchange {
        volatile ChannelClient.Channel channel;
        volatile InputStream input;
        final AtomicBoolean finished = new AtomicBoolean(), expired = new AtomicBoolean();
    }
    private volatile Exchange active;
    private volatile boolean closed;
    public RemoteClient(Context context) {
        client = Wearable.getChannelClient(context.getApplicationContext());
        capabilities = Wearable.getCapabilityClient(context.getApplicationContext());
    }
    public void verify(String node) throws Exception {
        CapabilityInfo info;
        try { info = await(capabilities.getCapability(RemoteProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)); }
        catch (Exception e) { throw new IOException("Remote controls require version 1.3 or newer on the watch. Update both APKs and start Receive from phone on the watch.", e); }
        for (Node candidate : info.getNodes()) if (candidate.getId().equals(node)) return;
        throw new IOException("This watch is not ready for remote controls. Install version 1.3 or newer and start Receive from phone on the watch.");
    }
    private <T> T await(Task<T> task) throws Exception { return Tasks.await(task, 30, TimeUnit.SECONDS); }
    public JSONObject call(String node, JSONObject request) throws Exception {
        if (closed) throw new IOException("Remote controls closed");
        Exchange exchange = new Exchange(); active = exchange;
        ScheduledFuture<?> deadline = timer.schedule(() -> { exchange.expired.set(true); disconnect(exchange); }, 45, TimeUnit.SECONDS);
        OutputStream output = null;
        try {
            Task<ChannelClient.Channel> opening = client.openChannel(node, RemoteProtocol.PATH + UUID.randomUUID());
            opening.addOnSuccessListener(channel -> { if (exchange.finished.get() || exchange.expired.get() || closed) client.close(channel); });
            exchange.channel = await(opening);
            if (closed || exchange.expired.get()) throw new IOException("Watch connection timed out");
            exchange.input = await(client.getInputStream(exchange.channel)); output = await(client.getOutputStream(exchange.channel));
            return RemoteProtocol.exchange(exchange.input, output, request);
        } catch (Exception e) {
            if (exchange.expired.get()) throw new IOException("The watch did not reply. Confirm Receive from phone is still running, then check command status.", e);
            throw e;
        } finally {
            exchange.finished.set(true); deadline.cancel(false); disconnect(exchange);
            if (active == exchange) active = null;
            if (output != null) try { output.close(); } catch (IOException ignored) {}
        }
    }
    private void disconnect(Exchange exchange) {
        if (exchange == null) return;
        ChannelClient.Channel channel = exchange.channel;
        if (channel != null) client.close(channel);
        InputStream stream = exchange.input;
        if (stream != null) try { stream.close(); } catch (IOException ignored) {}
    }
    @Override public void close() { closed = true; disconnect(active); timer.shutdownNow(); }
}
