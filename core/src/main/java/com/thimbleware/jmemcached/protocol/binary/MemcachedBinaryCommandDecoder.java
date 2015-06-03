package com.thimbleware.jmemcached.protocol.binary;

import com.thimbleware.jmemcached.Key;
import com.thimbleware.jmemcached.LocalCacheElement;
import com.thimbleware.jmemcached.CacheElement;
import com.thimbleware.jmemcached.protocol.Op;
import com.thimbleware.jmemcached.protocol.CommandMessage;
import com.thimbleware.jmemcached.protocol.exceptions.MalformedCommandException;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.FrameDecoder;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.nio.ByteOrder;

/**
 */
@ChannelHandler.Sharable
public class MemcachedBinaryCommandDecoder extends FrameDecoder {

    public static final Charset USASCII = Charset.forName("US-ASCII");

    public static enum BinaryOp {
        Get(0x00, Op.GET, false),
        Set(0x01, Op.SET, false),
        Add(0x02, Op.ADD, false),
        Replace(0x03, Op.REPLACE, false),
        Delete(0x04, Op.DELETE, false),
        Increment(0x05, Op.INCR, false),
        Decrement(0x06, Op.DECR, false),
        Quit(0x07, Op.QUIT, false),
        Flush(0x08, Op.FLUSH_ALL, false),
        GetQ(0x09, Op.GETQ, true),
        Noop(0x0A, Op.NOOP, false),
        Version(0x0B, Op.VERSION, false),
        GetK(0x0C, Op.GET, false, true),
        GetKQ(0x0D, Op.GETKQ, true, true),
        Append(0x0E, Op.APPEND, false),
        Prepend(0x0F, Op.PREPEND, false),
        Stat(0x10, Op.STATS, false),
        SetQ(0x11, Op.SET, true),
        AddQ(0x12, Op.ADD, true),
        ReplaceQ(0x13, Op.REPLACE, true),
        DeleteQ(0x14, Op.DELETE, true),
        IncrementQ(0x15, Op.INCR, true),
        DecrementQ(0x16, Op.DECR, true),
        QuitQ(0x17, Op.QUIT, true),
        FlushQ(0x18, Op.FLUSH_ALL, true),
        AppendQ(0x19, Op.APPEND, true),
        PrependQ(0x1A, Op.PREPEND, true),
        Verbosity(0x1B, Op.VERBOSITY, false),
        Touch(0x1C, Op.TOUCH, false),
        GAT(0x1D, Op.GAT, false),
        GATQ(0x1E, Op.GATQ, true),
        
        /*
        SASLListMechs(0x20, Op.NOOP, false),
        SASLAuth(0x21, Op.NOOP, false),
        SASLStep(0x22, Op.NOOP, false),
        RGet(0x30, Op.NOOP, false),
        RSet(0x31, Op.NOOP, false),
        RSetQ(0x32, Op.NOOP, true),
        RAppend(0x33, Op.NOOP, false),
        RAppendQ(0x34, Op.NOOP, true),
        RPrepend(0x35, Op.NOOP, false),
        RPrependQ(0x36, Op.NOOP, true),
        RDelete(0x37, Op.NOOP, false),
        RDeleteQ(0x38, Op.NOOP, true),
        RIncr(0x39, Op.NOOP, false),
        RIncrQ(0x3A, Op.NOOP, true),
        RDecr(0x3B, Op.NOOP, false),
        RDecrQ(0x3C, Op.NOOP, true),
        SetVBucket(0x3D, Op.NOOP, false),
        GetVBucket(0x3E, Op.NOOP, false),
        DelVBucket(0x3F, Op.NOOP, false),
        TAPConnect(0x40, Op.NOOP, false),
        TAPMutation(0x41, Op.NOOP, false),
        TAPDelete(0x42, Op.NOOP, false),
        TAPFlush(0x43, Op.NOOP, false),
        TAPOpaque(0x44, Op.NOOP, false),
        TAPVBucketSet(0x45, Op.NOOP, false),
        TAPCheckpointStart(0x46, Op.NOOP, false),
        TAPCheckpointEnd(0x47, Op.NOOP, false),
        */
        ;

        public byte code;
        public Op correspondingOp;
        public boolean noreply;
        public boolean addKeyToResponse = false;

        BinaryOp(int code, Op correspondingOp, boolean noreply) {
            this.code = (byte)code;
            this.correspondingOp = correspondingOp;
            this.noreply = noreply;
        }

        BinaryOp(int code, Op correspondingOp, boolean noreply, boolean addKeyToResponse) {
            this.code = (byte)code;
            this.correspondingOp = correspondingOp;
            this.noreply = noreply;
            this.addKeyToResponse = addKeyToResponse;
        }

        public static BinaryOp forCommandMessage(CommandMessage msg) {
            for (BinaryOp binaryOp : values()) {
                if (binaryOp.correspondingOp == msg.op && binaryOp.noreply == msg.noreply && binaryOp.addKeyToResponse == msg.addKeyToResponse) {
                    return binaryOp;
                }
            }

            return null;
        }

    }

    protected Object decode(ChannelHandlerContext channelHandlerContext, Channel channel, ChannelBuffer channelBuffer) throws Exception {

        // need at least 24 bytes, to get header
        if (channelBuffer.readableBytes() < 24) return null;

        // get the header
        channelBuffer.markReaderIndex();
        ChannelBuffer headerBuffer = ChannelBuffers.buffer(ByteOrder.BIG_ENDIAN, 24);
        channelBuffer.readBytes(headerBuffer);

        short magic = headerBuffer.readUnsignedByte();

        // magic should be 0x80
        if (magic != 0x80) {
            headerBuffer.resetReaderIndex();

            throw new MalformedCommandException("binary request payload is invalid, magic byte incorrect");
        }

        short opcode = headerBuffer.readUnsignedByte();
        short keyLength = headerBuffer.readShort();
        short extraLength = headerBuffer.readUnsignedByte();
        short dataType = headerBuffer.readUnsignedByte();   // unused
        short reserved = headerBuffer.readShort(); // unused
        int totalBodyLength = headerBuffer.readInt();
        int opaque = headerBuffer.readInt();
        long cas = headerBuffer.readLong();

        // we want the whole of totalBodyLength; otherwise, keep waiting.
        if (channelBuffer.readableBytes() < totalBodyLength) {
            channelBuffer.resetReaderIndex();
            return null;
        }

        // This assumes correct order in the enum. If that ever changes, we will have to scan for 'code' field.
        BinaryOp bcmd = BinaryOp.values()[opcode];

        Op cmdType = bcmd.correspondingOp;
        CommandMessage cmdMessage = CommandMessage.command(cmdType);
        cmdMessage.noreply = bcmd.noreply;
        cmdMessage.cas_key = cas;
        cmdMessage.opaque = opaque;
        cmdMessage.addKeyToResponse = bcmd.addKeyToResponse;

        // get extras. could be empty.
        ChannelBuffer extrasBuffer = ChannelBuffers.buffer(ByteOrder.BIG_ENDIAN, extraLength);
        channelBuffer.readBytes(extrasBuffer);

        // get the key if any
        if (keyLength != 0) {
            ChannelBuffer keyBuffer = ChannelBuffers.buffer(ByteOrder.BIG_ENDIAN, keyLength);
            channelBuffer.readBytes(keyBuffer);

            ArrayList<Key> keys = new ArrayList<Key>();
            keys.add(new Key(keyBuffer.copy()));

            cmdMessage.keys = keys;


            if (cmdType == Op.ADD ||
                    cmdType == Op.SET ||
                    cmdType == Op.REPLACE ||
                    cmdType == Op.APPEND ||
                    cmdType == Op.PREPEND)
            {

                int flags = extrasBuffer.capacity() != 0 ? (int) extrasBuffer.readUnsignedInt() : 0;
                int expire = extrasBuffer.capacity() != 0 ? (int) extrasBuffer.readUnsignedInt() : 0;

                // the remainder of the message -- that is, totalLength - (keyLength + extraLength) should be the payload
                int size = totalBodyLength - keyLength - extraLength;

                cmdMessage.element = new LocalCacheElement(new Key(keyBuffer.slice()), flags, fixExpire(expire), 0L);
                ChannelBuffer data = ChannelBuffers.buffer(size);
                channelBuffer.readBytes(data);
                cmdMessage.element.setData(data);
            } else if (cmdType == Op.INCR || cmdType == Op.DECR) {
                long amount = extrasBuffer.readLong();
                long initialValue = extrasBuffer.readLong();
                long expiration = extrasBuffer.readUnsignedInt();

                cmdMessage.incrInitial = initialValue;
                cmdMessage.incrAmount = amount;
                cmdMessage.incrExpiry = fixExpire(expiration);
            } else if (cmdType == Op.GAT || cmdType == Op.GATQ || cmdType == Op.TOUCH){
        	long expiration = extrasBuffer.readUnsignedInt();
        	cmdMessage.incrExpiry = fixExpire(expiration);
            }
        }

        return cmdMessage;
    }
    
    private long fixExpire(long expire){
	return (expire != 0) && expire < CacheElement.THIRTY_DAYS ? LocalCacheElement.Now() + expire : expire;
    }
}
