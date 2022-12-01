//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;

public class GzipResponse extends Response.Wrapper implements Callback, Invocable
{
    public static Logger LOG = LoggerFactory.getLogger(GzipResponse.class);
    private static final byte[] GZIP_HEADER = new byte[]{(byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0};

    public static final HttpField VARY_ACCEPT_ENCODING = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

    private enum GZState
    {
        MIGHT_COMPRESS, NOT_COMPRESSING, COMMITTING, COMPRESSING, FINISHED
    }

    private final AtomicReference<GZState> _state = new AtomicReference<>(GZState.MIGHT_COMPRESS);
    private final CRC32 _crc = new CRC32();

    private final Callback _callback;
    private final GzipFactory _factory;
    private final HttpField _vary;
    private final int _bufferSize;
    private final boolean _syncFlush;

    private DeflaterPool.Entry _deflaterEntry;
    private ByteBuffer _buffer;

    public GzipResponse(Request request, Response wrapped, Callback callback, GzipFactory factory, HttpField vary, int bufferSize, boolean syncFlush)
    {
        super(request, wrapped);

        _callback = callback;
        _factory = factory;
        _vary = vary;
        _bufferSize = bufferSize;
        _syncFlush = syncFlush;
    }

    @Override
    public void succeeded()
    {
        try
        {
            // We need to write nothing here to intercept the committing of the response
            // and possibly change headers in case write is never called.
            write(true, null, Callback.NOOP);
            _callback.succeeded();
        }
        finally
        {
            if (getRequest() instanceof GzipRequest gzipRequest)
                gzipRequest.destroy();
        }
    }

    @Override
    public void failed(Throwable x)
    {
        try
        {
            _callback.failed(x);
        }
        finally
        {
            if (getRequest() instanceof GzipRequest gzipRequest)
                gzipRequest.destroy();
        }
    }

    @Override
    public InvocationType getInvocationType()
    {
        return _callback.getInvocationType();
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        switch (_state.get())
        {
            case MIGHT_COMPRESS -> commit(last, callback, content);
            case NOT_COMPRESSING -> super.write(last, content, callback);
            case COMMITTING -> callback.failed(new WritePendingException());
            case COMPRESSING -> gzip(last, callback, content);
            default -> callback.failed(new IllegalStateException("state=" + _state.get()));
        }
    }

    private void addTrailer()
    {
        BufferUtil.putIntLittleEndian(_buffer, (int)_crc.getValue());
        BufferUtil.putIntLittleEndian(_buffer, _deflaterEntry.get().getTotalIn());
    }

    private void gzip(boolean complete, final Callback callback, ByteBuffer content)
    {
        if (content != null || complete)
            new GzipBufferCB(complete, callback, content).iterate();
        else
            callback.succeeded();
    }

    protected void commit(boolean last, Callback callback, ByteBuffer content)
    {
        // Are we excluding because of status?
        Response response = GzipResponse.this;
        Request request = response.getRequest();
        if (!request.isAccepted())
            throw new IllegalStateException("!accepted");

        int sc = response.getStatus();
        if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
        {
            LOG.debug("{} exclude by status {}", this, sc);
            noCompression();

            if (sc == HttpStatus.NOT_MODIFIED_304)
            {
                String requestEtags = (String)request.getAttribute(GzipHandler.GZIP_HANDLER_ETAGS);
                String responseEtag = response.getHeaders().get(HttpHeader.ETAG);
                if (requestEtags != null && responseEtag != null)
                {
                    String responseEtagGzip = etagGzip(responseEtag);
                    if (requestEtags.contains(responseEtagGzip))
                        response.getHeaders().put(HttpHeader.ETAG, responseEtagGzip);
                    if (_vary != null)
                        response.getHeaders().ensureField(_vary);
                }
            }

            super.write(last, content, callback);
            return;
        }

        // Are we excluding because of mime-type?
        String ct = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (ct != null)
        {
            String baseType = HttpField.valueParameters(ct, null);
            if (!_factory.isMimeTypeGzipable(baseType))
            {
                LOG.debug("{} exclude by mimeType {}", this, ct);
                noCompression();
                super.write(last, content, callback);
                return;
            }
        }

        // Has the Content-Encoding header already been set?
        HttpFields.Mutable fields = response.getHeaders();
        String ce = fields.get(HttpHeader.CONTENT_ENCODING);
        if (ce != null)
        {
            LOG.debug("{} exclude by content-encoding {}", this, ce);
            noCompression();
            super.write(last, content, callback);
            return;
        }

        // Are we the thread that commits?
        if (_state.compareAndSet(GZState.MIGHT_COMPRESS, GZState.COMMITTING))
        {
            // We are varying the response due to accept encoding header.
            if (_vary != null)
                fields.ensureField(_vary);

            long contentLength = response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            if (contentLength < 0 && last)
                contentLength = BufferUtil.length(content);

            _deflaterEntry = _factory.getDeflaterEntry(request, contentLength);
            if (_deflaterEntry == null)
            {
                LOG.debug("{} exclude no deflater", this);
                _state.set(GZState.NOT_COMPRESSING);
                super.write(last, content, callback);
                return;
            }

            fields.put(GZIP.getContentEncoding());
            _crc.reset();

            // Adjust headers
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, -1);
            String etag = fields.get(HttpHeader.ETAG);
            if (etag != null)
                fields.put(HttpHeader.ETAG, etagGzip(etag));

            LOG.debug("{} compressing {}", this, _deflaterEntry);
            _state.set(GZState.COMPRESSING);

            if (BufferUtil.isEmpty(content))
            {
                // We are committing, but have no content to compress, so flush empty buffer to write headers.
                super.write(last, content, callback);
            }
            else
            {
                gzip(last, callback, content);
            }
        }
        else
        {
            callback.failed(new WritePendingException());
        }
    }

    private String etagGzip(String etag)
    {
        return GZIP.etag(etag);
    }

    public void noCompression()
    {
        while (true)
        {
            switch (_state.get())
            {
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (_state.compareAndSet(GZState.MIGHT_COMPRESS, GZState.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }

    private class GzipBufferCB extends IteratingNestedCallback
    {
        private ByteBuffer _copy;
        private final ByteBuffer _content;
        private final boolean _last;

        public GzipBufferCB(boolean complete, Callback callback, ByteBuffer content)
        {
            super(callback);
            _content = content;
            _last = complete;
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (_deflaterEntry != null)
            {
                _deflaterEntry.release();
                _deflaterEntry = null;
            }
            super.onCompleteFailure(x);
        }

        @Override
        protected Action process() throws Exception
        {
            // If we have no deflater
            if (_deflaterEntry == null)
            {
                // then the trailer has been generated and written below.
                // We have finished compressing the entire content, so
                // cleanup and succeed.
                if (_buffer != null)
                {
                    getRequest().getComponents().getByteBufferPool().release(_buffer);
                    _buffer = null;
                }
                if (_copy != null)
                {
                    getRequest().getComponents().getByteBufferPool().release(_copy);
                    _copy = null;
                }
                return Action.SUCCEEDED;
            }

            // If we have no buffer
            if (_buffer == null)
            {
                // allocate a buffer and add the gzip header.
                _buffer = getRequest().getComponents().getByteBufferPool().acquire(_bufferSize, false);
                BufferUtil.fill(_buffer, GZIP_HEADER, 0, GZIP_HEADER.length);
            }
            else
            {
                // otherwise clear the buffer as previous writes will always fully consume.
                BufferUtil.clear(_buffer);
            }

            // If the deflater is not finished, then compress more data.
            Deflater deflater = _deflaterEntry.get();
            if (!deflater.finished())
            {
                if (deflater.needsInput())
                {
                    ByteBuffer content = _content != null ? _content : BufferUtil.EMPTY_BUFFER;

                    // If there is no more content available to compress,
                    // then we have either finished all content or just the current write.
                    if (BufferUtil.isEmpty(content))
                    {
                        if (_last)
                        {
                            deflater.finish();
                        }
                        else if (BufferUtil.isEmpty(_buffer))
                        {
                            return Action.SUCCEEDED;
                        }
                        else
                        {
                            GzipResponse.super.write(false, _buffer, this);
                            return Action.SCHEDULED;
                        }
                    }
                    else
                    {
                        // TODO: this part is wrong, as there is a ByteBuffer Deflater API
                        //  and we don't want to copy direct ByteBuffers.
                        //  The comment below about CRC32.update() is IMHO wrong, as
                        //  in Jetty 10 we use it without problems.

                        // If there is more content available to compress, we have to make sure
                        // it is available in an array for the current deflater API, maybe slicing
                        // of content.
                        ByteBuffer slice;
                        if (content.hasArray())
                        {
                            slice = content;
                        }
                        else
                        {
                            if (_copy == null)
                                _copy = getRequest().getComponents().getByteBufferPool().acquire(_bufferSize, false);
                            else
                                BufferUtil.clear(_copy);
                            slice = _copy;
                            BufferUtil.append(_copy, content);
                        }

                        // transfer the data from the slice to the deflater
                        byte[] array = slice.array();
                        int off = slice.arrayOffset() + slice.position();
                        int len = slice.remaining();
                        _crc.update(array, off, len);
                        // Ideally we would want to use the ByteBuffer API for Deflaters. However due the the ByteBuffer implementation
                        // of the CRC32.update() it is less efficient for us to use this rather than to convert to array ourselves.
                        _deflaterEntry.get().setInput(array, off, len);
                        slice.position(slice.position() + len);
                        if (_last && _content == null && BufferUtil.isEmpty(content))
                            deflater.finish();
                    }
                }

                // deflate the content into the available space in the buffer
                int off = _buffer.arrayOffset() + _buffer.limit();
                int len = BufferUtil.space(_buffer);
                int produced = deflater.deflate(_buffer.array(), off, len, _syncFlush ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH);
                _buffer.limit(_buffer.limit() + produced);
            }

            // If we have finished deflation and there is room for the trailer.
            if (deflater.finished() && BufferUtil.space(_buffer) >= 8)
            {
                // add the trailer and recycle the deflator to flag that we will have had completeSuccess when
                // the write below completes.
                addTrailer();
                _deflaterEntry.release();
                _deflaterEntry = null;
            }

            // write the compressed buffer.
            GzipResponse.super.write(_deflaterEntry == null, _buffer, this);
            return Action.SCHEDULED;
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s last=%b copy=%s buffer=%s deflate=%s %s]",
                super.toString(),
                BufferUtil.toDetailString(_content),
                _last,
                BufferUtil.toDetailString(_copy),
                BufferUtil.toDetailString(_buffer),
                _deflaterEntry,
                _deflaterEntry != null && _deflaterEntry.get().finished() ? "(finished)" : "");
        }
    }
}
