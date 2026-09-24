package dev.tachyonscript.runtime.interpreter;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HotSpot does not JIT-compile methods larger than 8000 bytes of bytecode
 * ({@code -XX:-DontCompileHugeMethods} is off by default). An interpreter loop above that
 * limit would stay in the bytecode interpreter forever, so this test guards the size.
 */
class JitFriendlinessTest {

    private static final int HUGE_METHOD_LIMIT = 8000;

    @Test
    void dispatchLoopIsSmallEnoughToBeJitCompiled() throws IOException {
        int size = codeLength(Interpreter.class, "execute");
        assertTrue(size > 0, "execute method not found");
        System.out.println("Interpreter.execute bytecode size: " + size + " bytes");
        assertTrue(size < HUGE_METHOD_LIMIT - 500, "Interpreter.execute is " + size + " bytes; move rare opcodes to helpers");
    }

    /** Reads the Code attribute length of a method straight from the class file. */
    static int codeLength(Class<?> type, String method) throws IOException {
        try (InputStream raw = type.getResourceAsStream(type.getSimpleName() + ".class");
             DataInputStream in = new DataInputStream(raw)) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("Unknown constant tag " + tag);
                }
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.skipBytes(2 * in.readUnsignedShort());
            skipMembers(in);
            int methods = in.readUnsignedShort();
            int found = -1;
            for (int m = 0; m < methods; m++) {
                in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                in.readUnsignedShort();
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    String attribute = utf8[in.readUnsignedShort()];
                    int length = in.readInt();
                    if (attribute.equals("Code") && name.equals(method)) {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        found = Math.max(found, in.readInt());
                        in.skipBytes(length - 8);
                    } else {
                        in.skipBytes(length);
                    }
                }
            }
            return found;
        }
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(6);
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.readUnsignedShort();
                in.skipBytes(in.readInt());
            }
        }
    }
}
