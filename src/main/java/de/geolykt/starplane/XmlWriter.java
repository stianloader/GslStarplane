package de.geolykt.starplane;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

public class XmlWriter extends BufferedWriter implements AutoCloseable {
    private static final String INDENT_STRING = "  ";

    private int indent = 0;

    public XmlWriter(Writer out) {
        super(out);
    }

    protected void writeIndent() throws IOException {
        for (int i = 0; i < this.indent; i++) {
            super.write(INDENT_STRING);
        }
    }

    public void writeStringAttr(String key, String value) throws IOException {
        this.writeIndent();
        super.write("<stringAttribute key=\"");
        super.write(key);
        super.write("\" value=\"");
        super.write(value.replace("\"", "\\&quot;"));
        super.write("\"/>");
        super.newLine();
    }

    public void writeBooleanAttr(String key, boolean value) throws IOException {
        this.writeIndent();
        super.write("<booleanAttribute key=\"");
        super.write(key);
        super.write("\" value=\"");
        super.write(Boolean.toString(value));
        super.write("\"/>");
        super.newLine();
    }

    public void writeListAttr(String key, List<String> values) throws IOException {
        this.writeIndent();
        super.write("<listAttribute key=\"");
        super.write(key);
        if (values.isEmpty()) {
            super.write("\"/>");
            super.newLine();
            return;
        } else {
            super.write("\">");
            super.newLine();
        }
        this.indent();
        for (String value : values) {
            this.writeIndent();
            super.write("<listEntry value=\"");
            super.write(value);
            super.write("\"/>");
            super.newLine();
        }
        this.unindent();
        this.writeIndent();
        super.write("</listAttribute>");
        super.newLine();
    }

    public void indent() {
        this.indent++;
    }

    public void unindent() {
        if (--this.indent == -1) {
            this.indent = 0;
            throw new IllegalStateException("indent < 0");
        }
    }
}
