package com.webtide.jetty.load.generator.jenkins.result;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import hudson.Extension;
import hudson.ExtensionPoint;
import org.kohsuke.stapler.DataBoundConstructor;
import org.mortbay.jetty.load.generator.listeners.CollectorInformations;
import org.mortbay.jetty.load.generator.listeners.LoadResult;
import org.mortbay.jetty.load.generator.listeners.ServerInfo;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Extension
public class CsvResultStore
    extends ResultStoreProvider
    implements ResultStore, ExtensionPoint
{

    private final static Logger LOGGER = Logger.getLogger( CsvResultStore.class.getName() );

    private final ReentrantLock lock = new ReentrantLock();

    private String fileName = "load_result.csv";

    private File csvFile;

    public CsvResultStore()
    {
        //no op
    }

    @DataBoundConstructor
    public CsvResultStore( String fileName )
    {
        this.fileName = fileName;
        // create the file if not exists
        this.csvFile = new File( ResultStoreManagement.getStoreDirectory().toFile(), fileName );
        if ( !Files.exists( this.csvFile.toPath() ) )
        {
            try
            {
                Files.createFile( this.csvFile.toPath() );
                // write csv headers
                writeStrings( new String[]{ "uuid", "processors", "jettyVersion", "memory", //
                    "minValue", "meanValue", "maxValue", "total", "start", "end", "value50", //
                    "value90", "stdDeviation" } );
            }
            catch ( IOException e )
            {
                String msg = "Cannot create file:" + this.csvFile;
                LOGGER.log( Level.SEVERE, msg, e );
                throw new RuntimeException( e.getMessage(), e );
            }
        }
    }

    @Override
    public String getProviderId()
    {
        return "csv";
    }

    @Override
    public ExtendedLoadResult save( LoadResult loadResult )
    {
        lock.lock();
        ExtendedLoadResult extendedLoadResult = new ExtendedLoadResult( UUID.randomUUID().toString(), loadResult );
        try
        {
            writeStrings( toCsv( extendedLoadResult ) );
        }
        catch ( IOException e )
        {
            String msg = "Cannot write entry:" + extendedLoadResult;
            LOGGER.log( Level.SEVERE, msg, e );
            throw new RuntimeException( e.getMessage(), e );
        }
        finally
        {
            lock.unlock();
        }
        return extendedLoadResult;
    }

    private void writeStrings( String[] values )
        throws IOException
    {
        try (CSVWriter writer = new CSVWriter( new FileWriter( this.csvFile, true ), ';',
                                               CSVWriter.DEFAULT_QUOTE_CHARACTER, CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                                               CSVWriter.DEFAULT_LINE_END ))
        {

            writer.writeNext( values );
            writer.flushQuietly();
        }

    }


    protected String[] toCsv( ExtendedLoadResult loadResult )
    {
        ServerInfo serverInfo = loadResult.getServerInfo();
        CollectorInformations collectorInformations = loadResult.getCollectorInformations();

        String uuid = loadResult.getUuid();
        int processors = serverInfo.getAvailableProcessors();
        String jettyVersion = serverInfo.getJettyVersion();
        long memory = serverInfo.getTotalMemory();
        long minValue = collectorInformations.getMinValue();
        double meanValue = collectorInformations.getMean();
        long maxValue = collectorInformations.getMaxValue();
        long total = collectorInformations.getTotalCount();
        long start = collectorInformations.getStartTimeStamp();
        long end = collectorInformations.getEndTimeStamp();
        long value50 = collectorInformations.getValue50();
        long value90 = collectorInformations.getValue90();
        double stdDeviation = collectorInformations.getStdDeviation();

        return new String[]{ uuid, String.valueOf( processors ), jettyVersion, String.valueOf( memory ), //
            String.valueOf( minValue ), String.valueOf( meanValue ), String.valueOf( maxValue ), //
            String.valueOf( total ), String.valueOf( start ), String.valueOf( end ), String.valueOf( value50 ), //
            String.valueOf( value90 ), String.valueOf( stdDeviation ) };


    }


    protected ExtendedLoadResult fromCsv( String[] values )
    {

        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setAvailableProcessors( Integer.valueOf( values[1] ) );
        serverInfo.setJettyVersion( values[2] );
        serverInfo.setTotalMemory( Long.valueOf( values[3] ) );

        CollectorInformations collectorInformations = new CollectorInformations();
        collectorInformations.setMinValue( Long.valueOf( values[4] ) );
        collectorInformations.setMean( Double.valueOf( values[5] ) );
        collectorInformations.setMaxValue( Long.valueOf( values[6] ) );
        collectorInformations.setTotalCount( Long.valueOf( values[7] ) );
        collectorInformations.setStartTimeStamp( Long.valueOf( values[8] ) );
        collectorInformations.setEndTimeStamp( Long.valueOf( values[9] ) );
        collectorInformations.setValue50( Long.valueOf( values[10] ) );
        collectorInformations.setValue90( Long.valueOf( values[11] ) );
        collectorInformations.setStdDeviation( Double.valueOf( values[12] ) );

        return new ExtendedLoadResult( values[0], new LoadResult( serverInfo, collectorInformations ) );
    }

    @Override
    public void remove( ExtendedLoadResult loadResult )
    {

    }

    @Override
    public List<ExtendedLoadResult> find( QueryFiler queryFiler )
    {
        // TODO filter on result
        return findAll();
    }

    @Override
    public List<ExtendedLoadResult> findAll()
    {
        lock.lock();
        try (CSVReader reader = new CSVReader( new FileReader( "yourfile.csv" ) ))
        {

            return Stream.generate( reader.iterator()::next ) //
                .map( strings -> fromCsv( strings ) ) //
                .collect( Collectors.toList() );

        }
        catch ( IOException e )
        {
            LOGGER.log( Level.SEVERE, e.getMessage(), e );
            throw new RuntimeException( e.getMessage(), e );
        }
        finally
        {
            lock.unlock();
        }
    }


}