package com.fasterxml.jackson.dataformat.protobuf;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.base.GeneratorBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.JsonWriteContext;
import com.fasterxml.jackson.dataformat.protobuf.schema.FieldType;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufField;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufMessage;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.WireType;

public class ProtobufGenerator extends GeneratorBase
{
    /**
     * Enumeration that defines all togglable features for Protobuf generators.
     * Currently none defined.
     */
    public enum Feature {
        ;

        protected final boolean _defaultState;
        protected final int _mask;
        
        /**
         * Method that calculates bit set (flags) of all features that
         * are enabled by default.
         */
        public static int collectDefaults()
        {
            int flags = 0;
            for (Feature f : values()) {
                if (f.enabledByDefault()) {
                    flags |= f.getMask();
                }
            }
            return flags;
        }
        
        private Feature(boolean defaultState) {
            _defaultState = defaultState;
            _mask = (1 << ordinal());
        }

        public boolean enabledIn(int flags) { return (flags & getMask()) != 0; }
        public boolean enabledByDefault() { return _defaultState; }
        public int getMask() { return _mask; }
    }

    /*
    /**********************************************************
    /* Constants
    /**********************************************************
     */

    /**
     * Since our context object does NOT implement standard write context, need
     * to do something like use a placeholder...
     */
    protected final static JsonWriteContext BOGUS_WRITE_CONTEXT = JsonWriteContext.createRootContext(null);
    
    /**
     * This instance is used as a placeholder for cases where we do not know
     * actual field and want to simply skip over any values that caller tries
     * to write for it.
     */
    protected final static ProtobufField UNKNOWN_FIELD = ProtobufField.unknownField();

    /**
     * This is used as a placeholder for case where we don't have an actual message
     * to use, but know (from context) that one is expected.
     */
    protected final static ProtobufMessage UNKNOWN_MESSAGE = ProtobufMessage.bogusMessage("<unknown>");

    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */

    final protected IOContext _ioContext;

    /**
     * Bit flag composed of bits that indicate which
     * {@link ProtobufGenerator.Feature}s
     * are enabled.
     */
    protected int _protobufFeatures;

    protected ProtobufSchema _schema;

    /*
    /**********************************************************
    /* Output state
    /**********************************************************
     */

    /**
     * Reference to the root context since that is needed for serialization
     */
    protected ProtobufWriteContext _rootContext;
    
    protected boolean _inObject;

    /**
     * Flag that indicates whether values should be written with tag or not;
     * false for packed arrays, true for others.
     */
    protected boolean _writeTag;
    
    /**
     * Flag that is set when the whole content is complete, can
     * be output.
     */
    protected boolean _complete;

    /**
     * Type of protobuf message that is currently being output: usually
     * matches write context, but for arrays may indicate "parent" of array.
     */
    protected ProtobufMessage _currMessage;
    
    /**
     * Field to be output next; set when {@link JsonToken#FIELD_NAME} is written,
     * cleared once value has been written
     */
    protected ProtobufField _currField;
    
    /*
    /**********************************************************
    /* Output buffering
    /**********************************************************
     */

    /**
     * Ultimate destination
     */
    final protected OutputStream _output;

    /**
     * Object used In cases where we need to buffer content to calculate length-prefix.
     */
    protected ByteAccumulator _buffered;
    
    /**
     * Current context, in form we can use it.
     */
    protected ProtobufWriteContext _pbContext;

    protected byte[] _currBuffer;

    protected int _currStart;
    
    protected int _currPtr;
    
    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */
    
    public ProtobufGenerator(IOContext ctxt, int jsonFeatures, int pbFeatures,
            ObjectCodec codec, OutputStream output)
        throws IOException
    {
        super(jsonFeatures, codec, BOGUS_WRITE_CONTEXT);
        _ioContext = ctxt;
        _protobufFeatures = pbFeatures;
        _output = output;
        _pbContext = ProtobufWriteContext.createNullContext();
        _currBuffer = ctxt.allocWriteEncodingBuffer();
    }

    public void setSchema(ProtobufSchema schema)
    {
        if (_schema == schema) {
            return;
        }
        _schema = schema;
        // start with temporary root...
//        _currentContext = _rootContext = ProtobufWriteContext.createRootContext(this, schema);
        _pbContext = _rootContext = ProtobufWriteContext.createRootContext(schema.getRootType());
    }
    
    /*                                                                                       
    /**********************************************************                              
    /* Versioned                                                                             
    /**********************************************************                              
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************
    /* Overridden methods, configuration
    /**********************************************************
     */

    /**
     * Not sure whether to throw an exception or just do no-op; for now,
     * latter.
     */
    @Override
    public ProtobufGenerator useDefaultPrettyPrinter() {
        return this;
    }

    @Override
    public ProtobufGenerator setPrettyPrinter(PrettyPrinter pp) {
        return this;
    }

    @Override
    public Object getOutputTarget() {
        return _output;
    }

    @Override
    public boolean canUseSchema(FormatSchema schema) {
        return (schema instanceof ProtobufSchema);
    }
    
    @Override public ProtobufSchema getSchema() {
        return _schema;
    }
    
    @Override
    public void setSchema(FormatSchema schema)
    {
        if (!(schema instanceof ProtobufSchema)) {
            throw new IllegalArgumentException("Can not use FormatSchema of type "
                    +schema.getClass().getName());
        }
        setSchema((ProtobufSchema) schema);
    }
    
    /*
    /**********************************************************************
    /* Overridden methods; writing field names
    /**********************************************************************
     */
    
    /* And then methods overridden to make final, streamline some
     * aspects...
     */

    @Override
    public final void writeFieldName(String name) throws IOException {
        _findField(name);
    }

    @Override
    public final void writeFieldName(SerializableString name) throws IOException {
        if (!_inObject) {
            _reportError("Can not write field name: current context not an OBJECT but "+_pbContext.getTypeDesc());
        }
        ProtobufField f = _currMessage.field(name);
        if (f == null) {
            // May be ok, if we have said so
            if ((_currMessage == UNKNOWN_MESSAGE)
                    || isEnabled(JsonGenerator.Feature.IGNORE_UNKNOWN)) {
                f = UNKNOWN_FIELD;
            } else {
                _reportError("Unrecognized field '"+name+"' (in Message of type "+_currMessage.getName()
                        +"); known fields are: "+_currMessage.fieldsAsString());
                        
            }
        }
        _pbContext.setField(f);
        _currField = f;
    }

    @Override
    public final void writeStringField(String fieldName, String value) throws IOException {
        _findField(fieldName);
        writeString(value);
    }

    private final void _findField(String id) throws IOException
    {
        if (!_inObject) {
            _reportError("Can not write field name: current context not an OBJECT but "+_pbContext.getTypeDesc());
        }
        ProtobufField f = _currMessage.field(id);
        if (f == null) {
            // May be ok, if we have said so
            if ((_currMessage == UNKNOWN_MESSAGE)
                    || isEnabled(JsonGenerator.Feature.IGNORE_UNKNOWN)) {
                f = UNKNOWN_FIELD;
            } else {
                _reportError("Unrecognized field '"+id+"' (in Message of type "+_currMessage.getName()
                        +"); known fields are: "+_currMessage.fieldsAsString());
                        
            }
        }
        _pbContext.setField(f);
        _currField = f;
    }

    /*
    /**********************************************************
    /* Extended API, configuration
    /**********************************************************
     */

    public ProtobufGenerator enable(Feature f) {
        _protobufFeatures |= f.getMask();
        return this;
    }

    public ProtobufGenerator disable(Feature f) {
        _protobufFeatures &= ~f.getMask();
        return this;
    }

    public final boolean isEnabled(Feature f) {
        return (_protobufFeatures & f.getMask()) != 0;
    }

    public ProtobufGenerator configure(Feature f, boolean state) {
        if (state) {
            enable(f);
        } else {
            disable(f);
        }
        return this;
    }

    /*
    /**********************************************************
    /* Public API: low-level I/O
    /**********************************************************
     */

    @Override
    public final void flush() throws IOException
    {
        // can only flush if we do not need accumulation for length prefixes
        if (_buffered == null) {
            int start = _currStart;
            int len = _currPtr - start;
            if (len > 0) {
                _currStart = 0;
                _currPtr = 0;
                _output.write(_currBuffer, start, len);
            }
        }
        _output.flush();
    }
    
    @Override
    public void close() throws IOException
    {
        super.close();
        if (isEnabled(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT)) {
            ProtobufWriteContext ctxt;
            while ((ctxt = _pbContext) != null) {
                if (ctxt.inArray()) {
                    writeEndArray();
                } else if (ctxt.inObject()) {
                    writeEndObject();
                } else {
                    break;
                }
            }
        }
        // May need to finalize...
        if (!_complete) {
            _complete();
        }
        if (_output != null) {
            if (_ioContext.isResourceManaged() || isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                _output.close();
            } else  if (isEnabled(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)) {
                // If we can't close it, we should at least flush
                _output.flush();
            }
        }
        // Internal buffer(s) generator has can now be released as well
        _releaseBuffers();
    }

    /*
    /**********************************************************
    /* Public API: structural output
    /**********************************************************
     */
    
    @Override
    public final void writeStartArray() throws IOException
    {
        // First: arrays only legal as Message (~= Object) fields:
        if (!_inObject) {
            _reportError("Current context not an OBJECT, can not write arrays");
        }
        if (_currField == null) { // just a sanity check
            _reportError("Can not write START_ARRAY without field (message type "+_currMessage.getName()+")");
        }
        if (!_currField.isArray()) {
            _reportError("Can not write START_ARRAY: field '"+_currField.name+"' not declared as 'repeated'");
        }

        // NOTE: do NOT clear _currField; needed for actual element type
        
        _pbContext = _pbContext.createChildArrayContext();
        _writeTag = !_currField.packed;
        /* Unpacked vs package: if unpacked, nothing special is needed, since it
         * is equivalent to just replicating same field N times.
         * With packed, need length prefix, all that stuff, so need accumulator
         */
        if (!_writeTag) { // packed
            // note: tag SHOULD be written for array itself, but not contents
            _startBuffering(_currField.typedTag);
        }
    }
    
    @Override
    public final void writeEndArray() throws IOException
    {
        if (!_pbContext.inArray()) {
            _reportError("Current context not an ARRAY but "+_pbContext.getTypeDesc());
        }
        _pbContext = _pbContext.getParent();
        if (_pbContext.inRoot()) {
            if (!_complete) {
                _complete();
            }
            _inObject = false;
        } else {
            _inObject = _pbContext.inObject();
        }

        // no arrays inside arrays, so parent can't be array and so:
        _writeTag = true; 
        // but, did we just end up packed array?
        if (_currField.packed) {
            _finishBuffering();
        }
    }

    @Override
    public final void writeStartObject() throws IOException
    {
        if (_currField == null) {
            // root?
            if (!_pbContext.inRoot()) {
                _reportError("Can not write START_OBJECT without field (message type "+_currMessage.getName()+")");
            }
            _currMessage = _schema.getRootType();
            // note: no buffering on root
        } else {
            // but also, field value must be Message if so
            if (!_currField.isObject()) {
                _reportError("Can not write START_OBJECT: type of field '"+_currField.name+"' not Message but: "+_currField.type);
            }
            _currMessage = _currField.getMessageType();
            // and we need to start buffering, or add more nesting
            // but we may or may not want to write tag for object
            if (_writeTag) {
                _startBuffering(_currField.typedTag);
            } else {
                _startBuffering();
            }
        }
        
        if (_inObject) {
            _pbContext = _pbContext.createChildObjectContext(_currMessage);
            _currField = null;
        } else { // must be array, then
            _pbContext = _pbContext.createChildObjectContext(_currMessage);
            // but do NOT clear next field here
            _inObject = true;
        }
        // even if within array, object fields use tags
        _writeTag = true; 
    }

    @Override
    public final void writeEndObject() throws IOException
    {
        if (!_inObject) {
            _reportError("Current context not an object but "+_pbContext.getTypeDesc());
        }
        _pbContext = _pbContext.getParent();
        if (_pbContext.inRoot()) {
            if (!_complete) {
                _complete();
            }
        } else {
            _currMessage = _pbContext.getMessageType();
        }
        _currField = _pbContext.getField();
        // possible that we might be within array, which might be packed:
        boolean inObj = _pbContext.inObject();
        _inObject = inObj;
        _writeTag = inObj || !_pbContext.inArray() || !_currField.packed;
        if (_buffered != null) { // null for root
            _finishBuffering();
        }
    }

    /*
    /**********************************************************
    /* Output method implementations, textual
    /**********************************************************
     */

    @Override
    public void writeString(String text) throws IOException
    {
        if (text == null) {
            writeNull();
            return;
        }
        // Couple of choices; short (guaranteed to have length <= 127); medium (guaranteed
        // to fit in single buffer); and large (something else)

        final int len = text.length();
        // since max encoded = 3*42 == 126
        if (len > 42) { // Oh yeah, so we FINALLY have the ultimate question answered here
            _encodeLongerString(text);
            return;
        }

        _verifyValueWrite();
        _ensureRoom(140); // 126 for bytes, 1 for length, 5 for tag, at least
        int ptr = _writeTag(WireType.LENGTH_PREFIXED, _currPtr) + 1; // +1 to leave room for length indicator
        final int start = ptr;
        final byte[] buf = _currBuffer;
        int i = 0;

        while (true) {
            int c = text.charAt(i);
            if (c > 0x7F) {
                break;
            }
            buf[ptr++] = (byte) c;
            if (++i >= len) { // done!
                buf[start-1] = (byte) (ptr - start);
                _currPtr = ptr;
                return;
            }
        }

        // no; non-aSCII stuff, slower loop
        while (i < len) {
            int c = text.charAt(i++);
            if (c <= 0x7F) {
                buf[ptr++] = (byte) c;
                continue;
            }
            // Nope, multi-byte:
            if (c < 0x800) { // 2-byte
                buf[ptr++] = (byte) (0xc0 | (c >> 6));
                buf[ptr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            // 3 or 4 bytes (surrogate)
            // Surrogates?
            if (c < SURR1_FIRST || c > SURR2_LAST) { // nope, regular 3-byte character
                buf[ptr++] = (byte) (0xe0 | (c >> 12));
                buf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                buf[ptr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            // Yup, a surrogate pair
            if (c > SURR1_LAST) { // must be from first range; second won't do
                _throwIllegalSurrogate(c);
            }
            // ... meaning it must have a pair
            if (i >= len) {
                _throwIllegalSurrogate(c);
            }
            c = _decodeSurrogate(c, text.charAt(i++));
            if (c > 0x10FFFF) { // illegal in JSON as well as in XML
                _throwIllegalSurrogate(c);
            }
            buf[ptr++] = (byte) (0xf0 | (c >> 18));
            buf[ptr++] = (byte) (0x80 | ((c >> 12) & 0x3f));
            buf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
            buf[ptr++] = (byte) (0x80 | (c & 0x3f));
        }
        buf[start-1] = (byte) (ptr - start);
        _currPtr = ptr;
    }
    
    @Override
    public void writeString(char[] text, int offset, int clen) throws IOException
    {
        if (text == null) {
            writeNull();
            return;
        }
        if (clen > 42) { // ... the ultimate question being "... that fits within 7-bit length indicator"
            _encodeLongerString(text, offset, clen);
            return;
        }
        _verifyValueWrite();
        _ensureRoom(140); // 126 for bytes, 1 for length, 5 for tag, at least
        int ptr = _writeTag(WireType.LENGTH_PREFIXED, _currPtr) + 1; // +1 to leave room for length indicator
        final int start = ptr;
        final byte[] buf = _currBuffer;
        final int end = offset + clen;

        while (true) {
            int c = text[offset];
            if (c > 0x7F) {
                break;
            }
            buf[ptr++] = (byte) c;
            if (++offset >= end) { // done!
                buf[start-1] = (byte) (ptr - start);
                _currPtr = ptr;
                return;
            }
        }
        while (offset < end) {
            int c = text[offset++];
            if (c <= 0x7F) {
                buf[ptr++] = (byte) c;
                continue;
            }
            if (c < 0x800) {
                buf[ptr++] = (byte) (0xc0 | (c >> 6));
                buf[ptr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            if (c < SURR1_FIRST || c > SURR2_LAST) {
                buf[ptr++] = (byte) (0xe0 | (c >> 12));
                buf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                buf[ptr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            if (c > SURR1_LAST) {
                _throwIllegalSurrogate(c);
            }
            // ... meaning it must have a pair
            if (offset >= end) {
                _throwIllegalSurrogate(c);
            }
            c = _decodeSurrogate(c, text[offset++]);
            if (c > 0x10FFFF) { // illegal in JSON as well as in XML
                _throwIllegalSurrogate(c);
            }
            buf[ptr++] = (byte) (0xf0 | (c >> 18));
            buf[ptr++] = (byte) (0x80 | ((c >> 12) & 0x3f));
            buf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
            buf[ptr++] = (byte) (0x80 | (c & 0x3f));
        }
        buf[start-1] = (byte) (ptr - start);
        _currPtr = ptr;
    }

    @Override
    public final void writeString(SerializableString sstr) throws IOException
    {
        _verifyValueWrite();
        byte[] b = sstr.asUnquotedUTF8();
        _writeLengthPrefixed(b,  0, b.length);
    }

    @Override
    public void writeRawUTF8String(byte[] text, int offset, int len) throws IOException
    {
        _verifyValueWrite();
        _writeLengthPrefixed(text, offset, len);
    }

    @Override
    public final void writeUTF8String(byte[] text, int offset, int len) throws IOException
    {
        _verifyValueWrite();
        _writeLengthPrefixed(text, offset, len);
    }

    private final static Charset UTF8 = Charset.forName("UTF-8");

    /*
    /**********************************************************
    /* Output method implementations, unprocessed ("raw")
    /**********************************************************
     */

    @Override
    public void writeRaw(String text) throws IOException {
        _reportUnsupportedOperation();
    }

    @Override
    public void writeRaw(String text, int offset, int len) throws IOException {
        _reportUnsupportedOperation();
    }

    @Override
    public void writeRaw(char[] text, int offset, int len) throws IOException {
        _reportUnsupportedOperation();
    }

    @Override
    public void writeRaw(char c) throws IOException {
        _reportUnsupportedOperation();
    }

    @Override
    public void writeRawValue(String text) throws IOException {
        _reportUnsupportedOperation();
    }

    @Override
    public void writeRawValue(String text, int offset, int len) throws IOException {
        _reportUnsupportedOperation();
    }

    @Override
    public void writeRawValue(char[] text, int offset, int len) throws IOException {
        _reportUnsupportedOperation();
    }

    /*
    /**********************************************************
    /* Output method implementations, base64-encoded binary
    /**********************************************************
     */
    
    @Override
    public void writeBinary(Base64Variant b64variant, byte[] data, int offset, int len) throws IOException
    {
        if (data == null) {
            writeNull();
            return;
        }
        _verifyValueWrite("write Binary value");

        // Unlike with some other formats, let's NOT Base64 encoded even if nominally
        // expecting String, and rather assume that if so, caller just provides as
        // raw bytes of String
        if (_currField.wireType != WireType.LENGTH_PREFIXED) {
            _reportError("Can not write binary value for for '"+_currField.name+"' (type "+_currField.type+")");
        }
        _ensureRoom(10);
        _writeLengthPrefixed(data, offset, len);
    }

    /*
    /**********************************************************
    /* Output method implementations, primitive
    /**********************************************************
     */

    @Override
    public void writeBoolean(boolean state) throws IOException
    {
        _verifyValueWrite();

        // same as 'writeNumber(int)', really
        final int type = _currField.wireType;

        if (type == WireType.VINT) { // first, common case
            _writeVInt(_currField.usesZigZag ? 2 : 1);
            return;
        }
        if (type == WireType.FIXED_32BIT) {
            _writeInt32(1);
            return;
        }
        if (type == WireType.FIXED_64BIT) {
            _writeInt64(1L);
            return;
        }
        _reportError("Can not write `boolean` value for '"+_currField.name+"' (type "+_currField.type+")");
    }

    @Override
    public void writeNull() throws IOException
    {
        _verifyValueWrite();

        // protobuf has no way of writing null does it?
        // ...but should we try to add placeholders in arrays?

        /* 10-Nov-2014, tatu: At least one problem: required fields...
         *   Let's complain about those? In future, could add placeholders
         *   for such cases.
         */
        if (_currField.required) {
            _reportError("Can not omit writing of `null` value for required field '"+_currField.name+"' (type "+_currField.type+")");
        }
    }
    
    @Override
    public void writeNumber(int v) throws IOException
    {
        _verifyValueWrite();

        final int type = _currField.wireType;

        if (type == WireType.VINT) { // first, common case
            if (_currField.usesZigZag) {
                v = ProtobufUtil.zigzagEncode(v);
            }
            _writeVInt(v);
            return;
        }
        if (type == WireType.FIXED_32BIT) {
            _writeInt32(v);
            return;
        }
        if (type == WireType.FIXED_64BIT) {
            _writeInt64(v);
            return;
        }
        _reportError("Can not write `int` value for for '"+_currField.name+"' (type "+_currField.type+")");
    }

    @Override
    public void writeNumber(long v) throws IOException
    {
        _verifyValueWrite();
        final int type = _currField.wireType;

        if (type == WireType.VINT) { // first, common case
            if (_currField.usesZigZag) {
                v = ProtobufUtil.zigzagEncode(v);
            }
            // is this ok?
            _writeVLong(v);
            return;
        }
        if (type == WireType.FIXED_32BIT) {
            _writeInt32((int) v);
            return;
        }
        if (type == WireType.FIXED_64BIT) {
            _writeInt64(v);
            return;
        }
        _reportError("Can not write `long` value for for '"+_currField.name+"' (type "+_currField.type+")");
    }

    @Override
    public void writeNumber(BigInteger v) throws IOException
    {
        if (v == null) {
            writeNull();
            return;
        }
        // !!! TODO: better scheme to detect overflow or something
        writeNumber(v.longValue());
    }
    
    @Override
    public void writeNumber(double d) throws IOException
    {
        _verifyValueWrite();
        final int type = _currField.wireType;

        if (type == WireType.FIXED_32BIT) {
            // should we coerce like this?
            float f = (float) d;
            _writeInt32(Float.floatToRawIntBits(f));
            return;
        }
        if (type == WireType.FIXED_64BIT) {
            _writeInt64(Double.doubleToLongBits(d));
            return;
        }
        if (_currField.type == FieldType.STRING) {
            _encodeLongerString(String.valueOf(d));
            return;
        }
        _reportError("Can not write `double` value for for '"+_currField.name+"' (type "+_currField.type+")");
    }    

    @Override
    public void writeNumber(float f) throws IOException
    {
        _verifyValueWrite();
        final int type = _currField.wireType;

        if (type == WireType.FIXED_32BIT) {
            _writeInt32(Float.floatToRawIntBits(f));
            return;
        }
        if (type == WireType.FIXED_64BIT) {
            _writeInt64(Double.doubleToLongBits((double) f));
            return;
        }
        if (_currField.type == FieldType.STRING) {
            _encodeLongerString(String.valueOf(f));
            return;
        }
        _reportError("Can not write `float` value for for '"+_currField.name+"' (type "+_currField.type+")");
    }

    @Override
    public void writeNumber(BigDecimal v) throws IOException
    {
        if (v == null) {
            writeNull();
            return;
        }
        // !!! TODO: better handling here... exception or write as string or... ?
        writeNumber(v.doubleValue());
    }

    @Override
    public void writeNumber(String encodedValue) throws IOException {
        throw new UnsupportedOperationException("Can not write 'untyped' numbers");
    }

    protected final void _verifyValueWrite() throws IOException {
        if (_currField == null) {
            _reportError("Can not write value without indicating field first (in message of type "+_currMessage.getName()+")");
        }
    }
    
    /*
    /**********************************************************
    /* Implementations for methods from base class
    /**********************************************************
     */
    
    @Override
    protected void _verifyValueWrite(String typeMsg) throws IOException {
        _throwInternal();
    }

    @Override
    protected void _releaseBuffers() {
        byte[] b = _currBuffer;
        if (b != null) {
            _ioContext.releaseWriteEncodingBuffer(b);
            _currBuffer = null;
        }
    }

    /*
    /**********************************************************
    /* Internal text/binary writes
    /**********************************************************
     */

    protected void _encodeLongerString(char[] text, int offset, int clen) throws IOException
    {
        _verifyValueWrite();
        byte[] b = new String(text, offset, clen).getBytes(UTF8);
        _writeLengthPrefixed(b, 0, b.length);
    }

    protected void _encodeLongerString(String text) throws IOException
    {
        byte[] b = text.getBytes(UTF8);
        _writeLengthPrefixed(b, 0, b.length);
    }
    
    
    protected void _writeLengthPrefixed(byte[] data, int offset, int len) throws IOException
    {
        int ptr = _writeTag(WireType.LENGTH_PREFIXED, _currPtr);
        ptr = ProtobufUtil.appendLengthLength(len, _currBuffer, ptr);

        // and then loop until we are done
        while (len > 0) {
            int max = Math.min(len, _currBuffer.length - ptr);
            System.arraycopy(data, offset, _currBuffer, ptr, max);
            ptr += max;
            if ((len -= max) == 0) {
                _currPtr = ptr;
                break;
            }
            offset += max;

            ByteAccumulator acc = _buffered;
            final int start = _currStart;
            _currStart = 0;
            int toFlush = ptr - start;
            ptr = 0;

            // without accumulation, we know buffer is free for reuse
            if (acc == null) {
                if (toFlush > 0) {
                    _output.write(_currBuffer, start, toFlush);
                }
                ptr = 0;
                continue;
            }
            // but with buffered, need to append, allocate new buffer (since old
            // almost certainly contains buffered data)
            if (toFlush > 0) {
                acc.append(_currBuffer, start, toFlush);
            }
            _currBuffer = ProtobufUtil.allocSecondary(_currBuffer);
        }
    }

    /*
    /**********************************************************
    /* Internal scalar value writes
    /**********************************************************
     */
    
    private final void _writeVInt(int v) throws IOException
    {
        // Max tag length 5 bytes, then at most 5 bytes
        _ensureRoom(10);
        int ptr = _writeTag(WireType.VINT, _currPtr);
        if (v < 0) {
            _currPtr = _writeVIntMax(v, ptr);
            return;
        }

        final byte[] buf = _currBuffer;
        if (v <= 0x7F) {
            buf[ptr++] = (byte) v;
        } else {
            buf[ptr++] = (byte) (0x80 + (v & 0x7F));
            v >>= 7;
            if (v <= 0x7F) {
                buf[ptr++] = (byte) v;
            } else {
                buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
                v >>= 7;
                if (v <= 0x7F) {
                    buf[ptr++] = (byte) v;
                } else {
                    buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
                    v >>= 7;
                    if (v <= 0x7F) {
                        buf[ptr++] = (byte) v;
                    } else {
                        buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
                        v >>= 7;
                        // and now must have at most 3 bits (since negatives were offlined)
                        buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
                    }
                }
            }
        }
        _currPtr = ptr;
    }

    // off-lined version for 5-byte VInts
    private final int _writeVIntMax(int v, int ptr) throws IOException
    {
        final byte[] buf = _currBuffer;
        buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
        v >>>= 7;
        buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
        v >>= 7;
        buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
        v >>= 7;
        buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
        v >>= 7;
        buf[ptr++] = (byte) v;
        return ptr;
    }
    
    private final void _writeVLong(long v) throws IOException
    {
        // Max tag length 5 bytes, then at most 5 bytes
        _ensureRoom(10);
        int ptr = _writeTag(WireType.VINT, _currPtr);
        if (v < 0L) {
            _currPtr = _writeVLongMax(v, ptr);
            return;
        }

        // first, 4 bytes or less?
        if (v <= 0x0FFFFFFF) {
            int i = (int) v;
            final byte[] buf = _currBuffer;

            if (v <= 0x7F) {
                buf[ptr++] = (byte) v;
            } else {
                do {
                    buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
                    i >>= 7;
                } while (i > 0x7F);
                buf[ptr++] = (byte) i;
            }
            _currPtr = ptr;
            return;
        }
        // nope, so we know 28 LSBs are to be written first
        int i = (int) v;
        final byte[] buf = _currBuffer;

        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);

        v >>>= 28;

        // still got 36 bits, chop of LSB
        if (v <= 0x7F) {
            buf[ptr++] = (byte) v;
        } else {
            buf[ptr++] = (byte) ((v & 0x7F) + 0x80);
            // but then can switch to int for remaining max 28 bits
            i = (int) (v >> 7);
            do {
                buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
                i >>= 7;
            } while (i > 0x7F);
            buf[ptr++] = (byte) i;
        }
        _currPtr = ptr;
    }

    // off-lined version for 10-byte VLongs
    private final int _writeVLongMax(long v, int ptr) throws IOException
    {
        final byte[] buf = _currBuffer;
        // first, LSB 28 bits
        int i = (int) v;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);

        // then next 28 (for 56 so far)
        i = (int) (v >>> 28);
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);

        // and last 2 (for 7 bits, and 1 bit, respectively)
        i = (int) (v >>> 56);
        buf[ptr++] = (byte) ((i & 0x7F) + 0x80);
        i >>= 7;
        buf[ptr++] = (byte) i;
        return ptr;
    }
    
    private final void _writeInt32(int v) throws IOException
    {
        final byte[] buf = _currBuffer;
        int ptr = _currPtr;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        _currPtr =  ptr;
    }
    
    private final void _writeInt64(long v64) throws IOException
    {
        final byte[] buf = _currBuffer;
        int ptr = _currPtr;

        int v = (int) v64;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;

        v = (int) (v64 >> 32);
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        v >>= 8;
        buf[ptr++] = (byte) v;
        
        _currPtr =  ptr;
    }

    /*
    /**********************************************************
    /* Helper methods, buffering
    /**********************************************************
     */

    private final int _writeTag(int wireType, int ptr)
    {
        if (_writeTag) {
            final byte[] buf = _currBuffer;
            int tag = _currField.typedTag;
            if (tag <= 0x7F) {
                buf[ptr++] = (byte) tag;
            } else {
                // Note: caller must have ensured space for at least 5 bytes
                do {
                    buf[ptr++] = (byte) ((tag & 0x7F) + 0x80);
                    tag >>= 7;
                } while (tag > 0x7F);
                buf[ptr++] = (byte) tag;
            }
        }
        return ptr;
    }

    /**
     * Method called when buffering an entry that should be prefixed
     * with a type tag.
     */
    private final void _startBuffering(int typedTag) throws IOException
    {
        // need to ensure room for tag id, length (10 bytes); might as well ask for bit more
        _ensureRoom(20);
        // and leave the gap of 10 bytes
        _currStart = _currPtr = _currPtr + 10;
        _buffered = new ByteAccumulator(_buffered, typedTag);
    }

    /**
     * Method called when buffering an entry that should not be prefixed
     * with a type tag.
     */
    private final void _startBuffering() throws IOException
    {
        // since no tag written, could skimp on space needed
        _ensureRoom(16);
        _currStart = _currPtr = _currPtr + 5;
        _buffered = new ByteAccumulator(_buffered);
    }

    private final void _finishBuffering() throws IOException
    {
        final int start = _currStart;
        final int currLen = _currPtr - start;
        
        ByteAccumulator acc = _buffered;
        acc = acc.finish(_output, _currBuffer, start, currLen);
        _buffered = acc;
        if (acc == null) {
            _currStart = 0;
            _currPtr = 0;
        } else {
            _currStart = _currPtr;
        }
    }

    protected final void _ensureRoom(int needed) throws IOException
    {
        // common case: we got it already
        if ((_currPtr + needed) <= _currBuffer.length) {
            return;
        }
        // if not, either simple (flush), or 
        final int start = _currStart;
        final int currLen = _currPtr - start;
        
        _currStart = 0;
        _currPtr = 0;

        ByteAccumulator acc = _buffered;
        if (acc == null) {
            // without accumulation, we know buffer is free for reuse
            if (currLen > 0) {
                _output.write(_currBuffer, start, currLen);
            }
            return;
        }
        // but with buffered, need to append, allocate new buffer (since old
        // almost certainly contains buffered data)
        if (currLen > 0) {
            acc.append(_currBuffer, start, currLen);
        }
        _currBuffer = ProtobufUtil.allocSecondary(_currBuffer);
    }

    protected void _complete() throws IOException
    {
        _complete = true;
        final int start = _currStart;
        final int currLen = _currPtr - start;
        _currPtr = start;

        ByteAccumulator acc = _buffered;
        if (acc == null) {
            if (currLen > 0) {
                _output.write(_currBuffer, start, currLen);
                _currStart = 0;
                _currPtr = 0;
            }
        } else {
            acc = acc.finish(_output, _currBuffer, start, currLen);
            while (acc != null) {
                acc = acc.finish(_output);
            }
            _buffered = null;
        }
    }

    /*
    /**********************************************************
    /* Helper methods, error reporting
    /**********************************************************
     */
    
    private void _throwIllegalSurrogate(int code)
    {
        if (code > 0x10FFFF) { // over max?
            throw new IllegalArgumentException("Illegal character point (0x"+Integer.toHexString(code)+") to output; max is 0x10FFFF as per RFC 4627");
        }
        if (code >= SURR1_FIRST) {
            if (code <= SURR1_LAST) { // Unmatched first part (closing without second part?)
                throw new IllegalArgumentException("Unmatched first part of surrogate pair (0x"+Integer.toHexString(code)+")");
            }
            throw new IllegalArgumentException("Unmatched second part of surrogate pair (0x"+Integer.toHexString(code)+")");
        }
        // should we ever get this?
        throw new IllegalArgumentException("Illegal character point (0x"+Integer.toHexString(code)+") to output");
    }
}
