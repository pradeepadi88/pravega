/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.client.segment.impl;

import io.netty.buffer.Unpooled;
import io.pravega.client.netty.impl.ConnectionFactory;
import io.pravega.client.netty.impl.RawClient;
import io.pravega.client.stream.impl.Controller;
import io.pravega.common.util.Retry;
import io.pravega.common.util.Retry.RetryWithBackoff;
import io.pravega.shared.protocol.netty.ConnectionFailedException;
import io.pravega.shared.protocol.netty.Reply;
import io.pravega.shared.protocol.netty.WireCommands;
import io.pravega.shared.protocol.netty.WireCommands.AppendSetup;
import io.pravega.shared.protocol.netty.WireCommands.ConditionalCheckFailed;
import io.pravega.shared.protocol.netty.WireCommands.DataAppended;
import io.pravega.shared.protocol.netty.WireCommands.WrongHost;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
class ConditionalOutputStreamImpl implements ConditionalOutputStream {
    private static final RetryWithBackoff RETRY_SCHEDULE = Retry.withExpBackoff(1, 10, 9, 30000);

    private final UUID writerId;
    private final Segment segmentId;
    private final Controller controller;
    private final ConnectionFactory connectionFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lock = new Object();
    @GuardedBy("lock")
    private RawClient client = null;
    
    private final String delegationToken;
    private final Supplier<Long> requestIdGenerator = new AtomicLong()::incrementAndGet;
    

    @Override
    public String getSegmentName() {
        return segmentId.getScopedName();
    }

    @Override
    public boolean write(ByteBuffer data, long expectedOffset) {
        synchronized (lock) { //Used to preserver order.
            long appendSequence = requestIdGenerator.get();
            return RETRY_SCHEDULE.retryingOn(ConnectionFailedException.class)
                    .throwingOn(NoSuchSegmentException.class)
                    .run(() -> {
                        if (client == null || client.isClosed()) {
                            client = new RawClient(controller, connectionFactory, segmentId);
                            long requestId = requestIdGenerator.get();
                            log.debug("Setting up append on segment: {}", segmentId);
                            val reply = client.sendRequest(requestId, new WireCommands.SetupAppend(requestId, writerId, segmentId.getScopedName(), delegationToken));
                            AppendSetup appendSetup = transformAppendSetup(reply.join());
                            if (appendSetup.getLastEventNumber() >= appendSequence) {
                                return true;
                            }
                        }
                        val reply = client.sendRequest(appendSequence, new WireCommands.ConditionalAppend(writerId, appendSequence, expectedOffset, Unpooled.wrappedBuffer(data)));
                        return transformDataAppended(reply.join());
                    });
        } 
    }

    @Override
    public void close() {
        log.info("Closing segment metadata connection for {}", segmentId);
        if (closed.compareAndSet(false, true)) {
            closeConnection("Closed call");
        }
    }
    
    @SneakyThrows(ConnectionFailedException.class)
    private AppendSetup transformAppendSetup(Reply reply) {
        if (reply instanceof AppendSetup) {
            return (AppendSetup) reply;
        } else {
            throw handelUnexpectedReply(reply);
        }
    }

    @SneakyThrows(ConnectionFailedException.class)
    private boolean transformDataAppended(Reply reply) {
        if (reply instanceof DataAppended) {
            return true;
        } else if (reply instanceof ConditionalCheckFailed) {
            return false;
        } else {
            throw handelUnexpectedReply(reply);
        }
    }
    
    private ConnectionFailedException handelUnexpectedReply(Reply reply) {
        closeConnection(reply.toString());
        if (reply instanceof WireCommands.NoSuchSegment) {
            throw new NoSuchSegmentException(reply.toString());
        } else if (reply instanceof WrongHost) {
            return new ConnectionFailedException(reply.toString());
        } else {
            return new ConnectionFailedException("Unexpected reply of " + reply + " when expecting an AppendSetup");
        }
    }
    
    private void closeConnection(String message) {
        log.info("Closing connection as a result of receiving: {}", message);
        RawClient c;
        synchronized (lock) {
            c = client;
            client = null;
        }
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Exception tearing down connection: ", e);
            }
        }
    }


}
