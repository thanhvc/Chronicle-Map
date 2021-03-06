/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.map;

/**
 * Created by Rob Austin
 */

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.engine.client.ClientWiredStatelessTcpConnectionHub;
import net.openhft.chronicle.engine.client.ParameterizeWireKey;
import net.openhft.chronicle.hash.ChronicleHashInstanceBuilder;
import net.openhft.chronicle.hash.impl.util.BuildVersion;
import net.openhft.chronicle.hash.replication.ReplicationHub;
import net.openhft.chronicle.map.ClientWiredStatelessChronicleEntrySet.EntrySetEventId;
import net.openhft.chronicle.network.WireHandler;
import net.openhft.chronicle.network.event.EventGroup;
import net.openhft.chronicle.network.event.WireHandlers;
import net.openhft.chronicle.wire.*;
import net.openhft.lang.io.DirectStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.openhft.chronicle.engine.client.ClientWiredStatelessTcpConnectionHub.CoreFields;
import static net.openhft.chronicle.engine.client.ClientWiredStatelessTcpConnectionHub.CoreFields.csp;
import static net.openhft.chronicle.engine.client.ClientWiredStatelessTcpConnectionHub.CoreFields.reply;
import static net.openhft.chronicle.engine.client.StringUtils.endsWith;
import static net.openhft.chronicle.map.AbstactStatelessClient.toParameters;
import static net.openhft.chronicle.map.MapWireHandler.EventId.*;
import static net.openhft.chronicle.map.MapWireHandler.Params.*;
import static net.openhft.chronicle.wire.Wires.acquireStringBuilder;

/**
 * @author Rob Austin.
 */
public class MapWireHandler<K, V> implements WireHandler, Consumer<WireHandlers> {

    private static final Logger LOG = LoggerFactory.getLogger(MapWireHandler.class);
    public static final int MAP_SERVICE = 3;
    public static final int SIZE_OF_SIZE = ClientWiredStatelessTcpConnectionHub.SIZE_OF_SIZE;
    private final Map<Long, Runnable> incompleteWork = new HashMap<>();
    private final ArrayList<BytesChronicleMap> bytesChronicleMaps = new ArrayList<>();

    @NotNull
    private final Supplier<ChronicleHashInstanceBuilder<ChronicleMap<K, V>>> mapFactory;
    private final Map<Long, CharSequence> cidToCsp;
    private final Map<CharSequence, Long> cspToCid = new HashMap<>();

    private Wire inWire = null;
    private Wire outWire = null;


    private void setCspTextFromCid(long cid) {
        cspText.setLength(0);
        cspText.append(cidToCsp.get(cid));
    }

    private final Consumer<WireIn> metaDataConsumer = new Consumer<WireIn>() {

        @Override
        public void accept(WireIn wireIn) {

            StringBuilder key = Wires.acquireStringBuilder();
            final ValueIn read = wireIn.read(key);


            if (csp.contentEquals(key)) {
                read.text(cspText);
            } else {
                if (CoreFields.cid.contentEquals(key)) {
                    setCspTextFromCid(read.int64());
                }
            }

            final int slash = cspText.lastIndexOf("/");
            final int hash = cspText.lastIndexOf("#");

            if (slash != -1 && slash < (cspText.length() - 1) &&
                    hash != -1 && hash < (cspText.length() - 1)) {
                final String channelStr = cspText.substring(slash + 1, hash);
                try {
                    channelId = MapWireHandler.this.acquireChannelId(channelStr);
                } catch (IOException e) {
                    // todo send to user
                    LOG.error("", e);
                }
            } else
                channelId = 0;

            tid = inWire.read(CoreFields.tid).int64();

        }
    };


    private WireHandlers publishLater;
    private byte localIdentifier;

    private int channelId;
    private Map<Integer, Replica> channels;
    private ReplicationHub hub;

    private ChronicleMap<String, Integer> channelNameToId;

    public MapWireHandler(
            @NotNull final Supplier<ChronicleHashInstanceBuilder<ChronicleMap<K, V>>> mapFactory,
            @NotNull final Supplier<ChronicleHashInstanceBuilder<ChronicleMap<String, Integer>>>
                    channelNameToIdFactory,
            @NotNull final ReplicationHub hub, byte localIdentifier,
            @NotNull final Map<Integer, Replica> channels,
            @NotNull final Map<Long, CharSequence> cidToCsp) {
        this(mapFactory, channelNameToIdFactory, hub, cidToCsp);
        this.channels = channels;
        this.localIdentifier = localIdentifier;

    }

    public MapWireHandler(
            @NotNull final Supplier<ChronicleHashInstanceBuilder<ChronicleMap<K, V>>> mapFactory,
            @NotNull final Supplier<ChronicleHashInstanceBuilder<ChronicleMap<String, Integer>>>
                    channelNameToIdFactory,
            @NotNull final ReplicationHub hub,
            @NotNull final Map<Long, CharSequence> cidToCsp) {

        this.mapFactory = mapFactory;
        this.hub = hub;
        this.cidToCsp = cidToCsp;

        try {
            channelNameToId = (ChronicleMap) channelNameToIdFactory.get()
                    .replicatedViaChannel(hub.createChannel(MAP_SERVICE)).create();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void accept(@NotNull final WireHandlers wireHandlers) {
        this.publishLater = wireHandlers;
    }




    @Override
    public void process(@NotNull final Wire in, @NotNull final Wire out) throws StreamCorruptedException {
        try {
            this.inWire = in;
            this.outWire = out;
            inWire.readDocument(metaDataConsumer, dataConsumer);
        } catch (Exception e) {
            LOG.error("", e);
        }
    }


    /**
     * @return the next free channel id
     */
    short getNextFreeChannel() {

        // todo this is a horrible hack, it slow and not processor safe, but was added to get
        // todo something working for now.

        int max = 3;
        for (Integer channel : channelNameToId.values()) {
            max = Math.max(max, channel);
        }

        return (short) (max + 1);
    }


    /**
     * gets the channel id for a name, or creates a new one if this name is not yet assosiated to a
     * channel
     *
     * @param fromName the name of the channel
     * @return the id assosiated with this name
     */
    int acquireChannelId(@NotNull final String fromName) throws IOException {

        // todo this is a horrible hack, it slow and NOT processor safe, but was added to get
        // todo something working for now.

        final Integer channelId = channelNameToId.get(fromName);

        if (channelId != null)
            return channelId;

        final int nextFreeChannel = getNextFreeChannel();
        try {

            mapFactory.get().replicatedViaChannel(hub.createChannel(nextFreeChannel)).create();
            channelNameToId.put(fromName, nextFreeChannel);

            return nextFreeChannel;
        } catch (IOException e) {
            // todo send this error back to the user
            LOG.error("", e);
            throw e;
        }

    }

    final StringBuilder cspText = new StringBuilder();
    public long tid;
    private final AtomicLong cid = new AtomicLong();

    /**
     * create a new cid if one does not already exist for this csp
     *
     * @param csp the csp we wish to check for a cid
     * @return the cid for this csp
     */
    private long createCid(CharSequence csp) {

        final long newCid = cid.incrementAndGet();
        final Long aLong = cspToCid.putIfAbsent(csp, newCid);

        if (aLong != null)
            return aLong;

        cidToCsp.put(newCid, csp.toString());
        return newCid;

    }


    private final Consumer<WireIn> dataConsumer = new Consumer<WireIn>() {

        @Override
        public void accept(WireIn wireIn) {

            final StringBuilder eventName = acquireStringBuilder();
            final ValueIn valueIn = inWire.readEventName(eventName);

            if (!incompleteWork.isEmpty()) {
                Runnable runnable = incompleteWork.get(CoreFields.tid);
                if (runnable != null) {
                    runnable.run();
                    return;
                }
            }

            final Bytes<?> outBytes = outWire.bytes();

            outWire.writeDocument(true, wire -> outWire.write(CoreFields.tid).int64(tid));

            try {

                if (put.contentEquals(eventName)) {

                    valueIn.marshallable(wire -> {

                        final Params[] params = putIfAbsent.params();
                        final byte[] key = wire.read(params[0]).bytes();
                        final byte[] value = wire.read(params[1]).bytes();

                        writeVoid(b -> ((Map<byte[], byte[]>) b.delegate).putIfAbsent(key, value));

                    });

                    return;
                }

                // -- THESE METHODS ARE USED BOTH MY MAP AND ENTRY-SET
                if (size.contentEquals(eventName)) {
                    write(b -> outWire.write(reply).int32(b.size()));
                    return;
                }

                if (isEmpty.contentEquals(eventName)) {
                    write(b -> {
                        final boolean result = b.isEmpty();
                        outWire.write(reply).bool(result);
                    });
                    return;
                }

                // -- THESE METHODS ARE ONLY ENTRY SET METHODS
                if (endsWith(cspText, "#entrySet")) {

                    // note :  remove on the key-set returns a boolean and on the map returns the
                    // old value
                    if (EntrySetEventId.remove.contentEquals(eventName)) {
                        write(b -> outWire.write(reply).bool(
                                b.delegate.remove(toByteArray(valueIn)) != null));
                        return;
                    }

                    // note :  remove on the key-set returns a boolean and on the map returns the
                    // old value
                    if (EntrySetEventId.iterator.contentEquals(eventName)) {
                        write(b -> {
                                    final ValueOut valueOut = outWire.writeEventName(() -> "entry");
                                    b.delegate.entrySet().forEach(e ->
                                            valueOut.sequence(toParameters(put,
                                                    e.getKey(),
                                                    e.getValue())));
                                }

                        );
                        return;
                    }


                    throw new IllegalStateException("unsupported event=" + eventName);
                }


                // -- THESE METHODS ARE ONLY MAP METHODS

                if (keySet.contentEquals(eventName)) {
                    throw new UnsupportedOperationException("todo");
                }

                if (values.contentEquals(eventName)) {
                    throw new UnsupportedOperationException("todo");
                }


                if (entrySet.contentEquals(eventName)) {
                    write(b -> outWire.write(reply).type("set-proxy").writeValue()

                            .marshallable(w -> {
                                        CharSequence root = cspText.subSequence(0, cspText
                                                .length() - "#map".length());

                                        final StringBuilder csp = acquireStringBuilder()
                                                .append(root)
                                                .append("#entrySet");

                                        w.write(CoreFields.csp).text(csp);
                                        w.write(CoreFields.cid).int64(createCid(csp));
                                    }

                            ));
                    return;
                }

                if (longSize.contentEquals(eventName)) {
                    write(b -> outWire.write(reply).int64(b.longSize()));
                    return;
                }


                if (containsKey.contentEquals(eventName)) {
                    // todo remove the    toByteArray(..)
                    write(b -> outWire.write(reply)
                            .bool(b.delegate.containsKey(toByteArray(valueIn))));
                    return;
                }

                if (containsValue.contentEquals(eventName)) {
                    // todo remove the    toByteArray(..)
                    write(b -> outWire.write(reply)
                            .bool(b.delegate.containsValue(toByteArray(valueIn))));
                    return;
                }

                if (get.contentEquals(eventName)) {
                    // todo remove the    toByteArray(..)
                    writeValueUsingDelegate(map -> {
                        final byte[] key = toByteArray(valueIn);
                        final byte[] value = map.get(key);
                        return value;
                    });
                    return;
                }

                if (getAndPut.contentEquals(eventName)) {

                    valueIn.marshallable(wire -> {

                        final Params[] params = getAndPut.params();

                        MapWireHandler.this.writeValue(b -> b.put(
                                wire.read(params[0]).bytes(),
                                wire.read(params[1]).bytes()));

                    });

                    return;
                }

                if (remove.contentEquals(eventName)) {
                    writeValue(b -> b.remove(toByteArray(valueIn)));
                    return;
                }

                if (clear.contentEquals(eventName)) {
                    writeVoid(BytesChronicleMap::clear);
                    return;
                }

                if (putAll.contentEquals(eventName)) {

                    final Map data = new HashMap();

                    writeVoid(b -> {
                        while (valueIn.hasNext()) {
                            valueIn.sequence(v -> valueIn.marshallable(wire -> data.put(
                                    wire.read(put.params()[0]).bytes(),
                                    wire.read(put.params()[1]).bytes())));
                        }
                        b.delegate.putAll(data);

                    });

                    return;
                }



                if (replace.contentEquals(eventName)) {


                    // todo fix this this is a hack to get to work for now.
                    // todo may use something like :
                    // todo bytesMap.replace(reader, reader, timestamp, identifier());

                    valueIn.marshallable(wire -> {
                        final Params[] params = replace.params();
                        final byte[] key = wire.read(params[0]).bytes();
                        final byte[] value = wire.read(params[1]).bytes();

                        writeValueFromBytes(b -> ((Map<byte[], byte[]>) b.delegate).replace(key, value));

                    });


                    return;
                }

                if (replaceWithOldAndNewValue.contentEquals(eventName)) {
                    write(bytesMap -> {
                        final net.openhft.lang.io.Bytes reader = toReader(valueIn,
                                replaceWithOldAndNewValue.params());
                        boolean result = bytesMap.replace(reader, reader, reader);
                        outWire.write(reply).bool(result);
                    });

                    return;
                }

                if (putIfAbsent.contentEquals(eventName)) {
                    valueIn.marshallable(wire -> {
                        final Params[] params = putIfAbsent.params();
                        final byte[] key = wire.read(params[0]).bytes();
                        final byte[] value = wire.read(params[1]).bytes();

                        writeValueFromBytes(b -> ((Map<byte[], byte[]>) b.delegate).putIfAbsent(key, value));

                    });

                    return;
                }

                if (removeWithValue.contentEquals(eventName)) {
                    write(bytesMap -> {
                        final net.openhft.lang.io.Bytes reader = toReader(valueIn, removeWithValue.params());
                        // todo call   outWire.write(result)
                        // .bool(bytesMap.remove(reader, reader, timestamp, identifier()));
                        outWire.write(reply).bool(bytesMap.remove(reader, reader));
                    });
                    return;
                }


                if (getApplicationVersion.contentEquals(eventName)) {
                    write(b -> outWire.write(reply).text(applicationVersion()));
                    return;
                }

                if (persistedDataVersion.contentEquals(eventName)) {
                    write(b -> outWire.write(reply).text(persistedDataVersion()));
                    return;
                }

                if (hashCode.contentEquals(eventName)) {
                    write(b -> outWire.write(reply).int32(b.hashCode()));
                    return;
                }

                throw new IllegalStateException("unsupported event=" + eventName);

            } catch (Exception e) {
                LOG.error("", e);
            } finally {

                if (EventGroup.IS_DEBUG) {
                    long len = outBytes.position() - SIZE_OF_SIZE;
                    if (len == 0) {
                        System.out.println("--------------------------------------------\n" +
                                "server writes:\n\n<EMPTY>");
                    } else {


                        System.out.println("--------------------------------------------\n" +
                                "server writes:\n\n" +
                                Wires.fromSizePrefixedBlobs(outBytes, SIZE_OF_SIZE, len));

                    }
                }
            }

        }
    };


    private void putAll(long tid) {

        // todo
    }

    @SuppressWarnings("SameReturnValue")
    private void writeValueFromBytes(final Function<BytesChronicleMap, byte[]> f) {

        write(b -> {

            byte[] fromBytes = f.apply(b);
            boolean isNull = fromBytes == null || fromBytes.length == 0;
            outWire.write(reply);

            if (isNull)
                outWire.writeValue().text(null);
            else {
                Bytes<?> bytes = outWire.bytes();
                bytes.write(fromBytes);
            }

        });
    }


    @NotNull
    private CharSequence persistedDataVersion() {
        final BytesChronicleMap bytesChronicleMap = bytesMap(channelId);
        if (bytesChronicleMap == null)
            return "";
        return bytesChronicleMap.delegate.persistedDataVersion();
    }

    @NotNull
    private CharSequence applicationVersion() {
        return BuildVersion.version();
    }

    /**
     * creates a lang buffer that holds just the payload of the args
     *
     * @return a new lang buffer containing the bytes of the args
     */
    private net.openhft.lang.io.Bytes toReader(ValueIn valueIn, Params... params) {

        // todo this is a bit of a hack
        final Bytes<?> bytes1 = valueIn.wireIn().bytes();
        final long inSize = bytes1.remaining();

        final net.openhft.lang.io.Bytes bytes = DirectStore.allocate(inSize + 1024).bytes();

        valueIn.marshallable(wire -> {

            for (Params p : params) {

                final ValueIn read = wire.read(p);

                final byte[] data = read.bytes();

                bytes.writeStopBit(data.length);
                bytes.write(data);
            }

        });


        return bytes.flip();
    }


    private byte[] toByteArray(ValueIn valueIn) {
        final String text = valueIn.text();
        return text.getBytes();
    }

    /**
     * gets the map for this channel id
     *
     * @param channelId the ID of the map
     * @return the chronicle map with this {@code channelId}
     */
    @NotNull
    private ReplicatedChronicleMap map(int channelId) {

        // todo this cast is a bit of a hack, improve later
        final ReplicatedChronicleMap map =
                (ReplicatedChronicleMap) channels.get(channelId);

        if (map != null)
            return map;

        throw new IllegalStateException();
    }

    /**
     * this is used to push the data straight into the entry in memory
     *
     * @param channelId the ID of the map
     * @return a BytesChronicleMap used to update the memory which holds the chronicle map
     */
    @Nullable
    private BytesChronicleMap bytesMap(int channelId) {

        final BytesChronicleMap bytesChronicleMap = (channelId < bytesChronicleMaps.size())
                ? bytesChronicleMaps.get(channelId)
                : null;

        if (bytesChronicleMap != null)
            return bytesChronicleMap;

        // grow the array
        for (int i = bytesChronicleMaps.size(); i <= channelId; i++) {
            bytesChronicleMaps.add(null);
        }

        final ReplicatedChronicleMap delegate = map(channelId);
        final BytesChronicleMap element = new BytesChronicleMap(delegate);
        bytesChronicleMaps.set(channelId, element);
        return element;

    }

    @SuppressWarnings("SameReturnValue")
    private void writeValue(final Function<Map<byte[], byte[]>, byte[]> f) {
        writeValueFromBytes(b -> {
            final Map<byte[], byte[]> delegate = (Map) b.delegate;
            return f.apply(delegate);
        });
    }

    @SuppressWarnings("SameReturnValue")
    private void writeValueUsingDelegate(final Function<ChronicleMap<byte[], byte[]>, byte[]> f) {

        write(b -> {

            byte[] result = f.apply((ChronicleMap<byte[], byte[]>) b.delegate);
            boolean isNull = result == null || result.length == 0;

            outWire.write(reply);
            final Bytes<?> bytes = outWire.bytes();

            if (isNull)
                outWire.writeValue().text(null);
            else
                bytes.write(result);

        });


    }

    @SuppressWarnings("SameReturnValue")
    private void write(@NotNull Consumer<BytesChronicleMap> c) {

        final BytesChronicleMap bytesMap = bytesMap(channelId);

        if (bytesMap == null) {
            LOG.error("no map for channelId=" + channelId + " can be found.");
            return;
        }

        bytesMap.output = null;

        outWire.writeDocument(false, out -> {
            long position = 0;
            try {
                position = outWire.bytes().position();
                c.accept(bytesMap);
            } catch (Exception e) {
                //      bytes.reset();
                // the idea of wire is that is platform independent,
                // so we wil have to send the exception as a String
                outWire.bytes().position(position);

                final WireOut o = out.write(reply)
                        .type(e.getClass().getSimpleName());

                if (e.getMessage() != null) {
                    o.writeValue().text(e.getMessage());
                }
                LOG.error("", e);
            }
        });


    }


    @SuppressWarnings("SameReturnValue")
    private void writeVoid(@NotNull Consumer<BytesChronicleMap> process) {

        // skip 4 bytes where we will write the size
        final BytesChronicleMap bytesMap = bytesMap(channelId);

        if (bytesMap == null) {
            LOG.error("no map for channelId=" + channelId + " can be found.");
            return;
        }


        try {
            outWire.bytes().mark();
            process.accept(bytesMap);

        } catch (Exception e) {
            outWire.writeDocument(false, out -> {
                //      bytes.reset();
                // the idea of wire is that is platform independent,
                // so we wil have to send the exception as a String
                outWire.bytes().reset();
                out.write(reply)
                        .type(e.getClass().getSimpleName())
                        .writeValue().text(e.getMessage());
                LOG.error("", e);
            });
        }


    }


    public void localIdentifier(byte localIdentifier) {
        this.localIdentifier = localIdentifier;
    }

    enum Params implements WireKey {
        key,
        value,
        oldValue,
        newValue
    }

    enum EventId implements ParameterizeWireKey {
        longSize,
        size,
        isEmpty,
        containsKey(key),
        containsValue(value),
        get(key),
        getAndPut(key, value),
        put(key, value),
        remove(key),
        removeWithoutAcc(key),
        clear,
        keySet,
        values,
        entrySet,
        entrySetRestricted,
        replace(key, value),
        replaceWithOldAndNewValue(key, oldValue, newValue),
        putIfAbsent(key, value),
        removeWithValue(key, value),
        toString,
        getApplicationVersion,
        persistedDataVersion,
        putAll,
        putAllWithoutAcc,
        hashCode,
        mapForKey,
        putMapped,
        keyBuilder,
        valueBuilder,
        createChannel,
        remoteIdentifier;

        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        public <P extends WireKey> P[] params() {
            return (P[]) this.params;

        }
    }


}

