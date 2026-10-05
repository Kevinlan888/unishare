package com.unishare.sdk.internal;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Private UTF-8 spool. Encoding buffers are bounded; partial files never escape. */
public final class StagedText {
    public static File create(File directory, String text, long limit) throws IOException {
        // Count without allocating a byte array, including Java's replacement for
        // an unpaired surrogate. Reject before creating a file or native stream.
        long count = 0;
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if(c<0x80) count++;
            else if(c<0x800) count+=2;
            else if(Character.isHighSurrogate(c)&&i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1))) {count+=4;i++;}
            else if(Character.isSurrogate(c)) count++;
            else count+=3;
            if(count>limit) throw new NativePortException(14,false,0);
        }
        File file=File.createTempFile("unishare-text-", ".utf8",directory);
        try {
            try(Writer writer=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)) {
                // Writer.write(String) itself must not allocate text.length chars.
                for(int offset=0;offset<text.length();offset+=8192) writer.write(text,offset,Math.min(8192,text.length()-offset));
            }
            return file;
        } catch(Throwable failure) {file.delete();throw failure;}
    }
    private StagedText() {}
}
