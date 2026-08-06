/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CsvSupportTest {

    @Test
    public void signedNumericFieldsRemainMachineReadable() throws Exception {
        String row = CsvSupport.joinRow(Arrays.asList("-1.25", "+2.5e3"));

        assertEquals("-1.25,+2.5e3", row);
        assertEquals("-1.25", CsvSupport.parseRecord(row)[0]);
    }

    @Test
    public void swallowedPrintWriterFailureIsPromotedToIOException() {
        PrintWriter writer = new PrintWriter(new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length)
                    throws IOException {
                throw new IOException("disk full");
            }

            @Override public void flush() {}
            @Override public void close() {}
        });
        writer.println("record");

        try {
            CsvSupport.requireNoError(writer, new File("manifest.csv"));
            fail("Expected checked write failure");
        } catch (IOException expected) {
            assertEquals("Could not write "
                    + new File("manifest.csv").getAbsolutePath() + ".",
                    expected.getMessage());
        }
    }
}
