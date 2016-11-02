//
//  ========================================================================
//  Copyright (c) 1995-2016 Webtide LLC, Olivier Lamy
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================

package com.webtide.jetty.load.generator.jenkins;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import org.eclipse.jetty.load.generator.ValueListener;
import org.eclipse.jetty.load.generator.latency.LatencyTimeListener;
import org.eclipse.jetty.load.generator.responsetime.ResponseTimeListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 */
public class ValuesFileWriter
    implements ResponseTimeListener, Serializable, EventHandler<ValueListener.Values>,
    EventFactory<ValueListener.Values>, LatencyTimeListener
{

    private final String filePath;

    private transient BufferedWriter bufferedWriter;

    private transient RingBuffer<Values> ringBuffer;

    public ValuesFileWriter( Path path )
    {
        try
        {
            this.filePath = path.toAbsolutePath().toString();
            this.bufferedWriter = Files.newBufferedWriter( path );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }

    }

    protected void onValues( Values values )
    {
        this.ringBuffer.publishEvent( ( event, sequence ) -> event.eventTimestamp( values.getEventTimestamp() ) //
            .method( values.getMethod() ) //
            .path( values.getPath() ) //
            .time( values.getTime() ) //
            .status( values.getStatus() ) //
            .size( values.getSize() ) );
    }

    @Override
    public void onLatencyTimeValue( Values values )
    {
        onValues( values );
    }

    @Override
    public void onResponseTimeValue( Values values )
    {
        onValues( values );
    }

    public Object readResolve()
    {
        try
        {
            this.bufferedWriter = Files.newBufferedWriter( Paths.get( this.filePath ) );

            // Executor that will be used to construct new threads for consumers
            ExecutorService executor = Executors.newCachedThreadPool();

            // Specify the size of the ring buffer, must be power of 2.
            int bufferSize = 1024;

            // Construct the Disruptor
            Disruptor<Values> disruptor = new Disruptor<>( this, bufferSize, executor );

            // Connect the handler
            disruptor.handleEventsWith( this );

            // Start the Disruptor, starts all threads running
            disruptor.start();

            this.ringBuffer = disruptor.getRingBuffer();

        }
        catch ( Exception e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        return this;
    }

    @Override
    public void onEvent( Values values, long l, boolean b )
        throws Exception
    {
        try
        {
            StringBuilder sb = new StringBuilder( 64 ) //
                .append( values.getEventTimestamp() ).append( '|' ) //
                .append( values.getMethod() ).append( '|' ) //
                .append( values.getPath() ).append( '|' ) //
                .append( values.getTime() ).append( '|' ) //
                .append( values.getStatus() ).append( '|' ) //
                .append( values.getSize() );

            this.bufferedWriter.write( sb.toString() );
            this.bufferedWriter.newLine();
        }
        catch ( IOException e )
        {
            e.printStackTrace();
        }
    }

    @Override
    public Values newInstance()
    {
        return new Values();
    }


    @Override
    public void onLoadGeneratorStop()
    {
        try
        {
            this.bufferedWriter.flush();
            this.bufferedWriter.close();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        System.out.println( "stop loadGenerator" );
    }

}