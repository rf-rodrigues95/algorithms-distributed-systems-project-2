package protocols.statemachine.messages;

import io.netty.buffer.ByteBuf;
import org.apache.commons.codec.binary.Hex;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import java.util.UUID;

public class LeaderOrderMessage extends ProtoMessage {

    public final static short MSG_ID = 124;

    private final UUID opId;
    private final int instance;
    private final byte[] op;

    public LeaderOrderMessage(int instance, UUID opId, byte[] op) {
        super(MSG_ID);
        this.instance = instance;
        this.op = op;
        this.opId = opId;
    }

    public int getInstance() {
        return instance;
    }

    public UUID getOpId() {
        return opId;
    }

    public byte[] getOp() {
        return op;
    }

    @Override
    public String toString() {
        return "LeaderOrderMessage{" +
                "opId=" + opId +
                ", instance=" + instance +
                ", op=" + Hex.encodeHexString(op) +
                '}';
    }

    public static ISerializer<LeaderOrderMessage> serializer = new ISerializer<LeaderOrderMessage>() {
        @Override
        public void serialize(LeaderOrderMessage msg, ByteBuf out) {
            out.writeInt(msg.instance);
            out.writeLong(msg.opId.getMostSignificantBits());
            out.writeLong(msg.opId.getLeastSignificantBits());
            out.writeInt(msg.op.length);
            out.writeBytes(msg.op);
        }

        @Override
        public LeaderOrderMessage deserialize(ByteBuf in) {
            int instance = in.readInt();
            long highBytes = in.readLong();
            long lowBytes = in.readLong();
            UUID opId = new UUID(highBytes, lowBytes);
            byte[] op = new byte[in.readInt()];
            in.readBytes(op);
            return new LeaderOrderMessage(instance, opId, op);
        }
    };

}
