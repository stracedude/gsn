package gsn;

import gsn.beans.DataField;
import gsn.beans.StreamElement;
import gsn.beans.VSensorConfig;
import gsn.http.rest.DeliverySystem;
import gsn.http.rest.DistributionRequest;
import gsn.storage.DataEnumerator;
import gsn.storage.SQLValidator;
import gsn.storage.StorageManager;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

public class DataDistributer implements VirtualSensorDataListener, VSensorStateChangeListener, Runnable {

    public static final int KEEP_ALIVE_PERIOD =  10 * 60 * 1000;  // 10 minutes.
    
    private javax.swing.Timer keepAliveTimer = null;

    private static transient Logger logger = Logger.getLogger(DataDistributer.class);

    private static HashMap<Class<? extends DeliverySystem>, DataDistributer> singletonMap = new HashMap<Class<? extends DeliverySystem>, DataDistributer>();
    private Thread thread;

    private DataDistributer() {
        try {
            db = StorageManager.getInstance().getConnection();
            thread = new Thread(this);
            thread.start();
            // Start the keep alive Timer -- Note that the implementation is backed by one single thread for all the RestDelivery instances.
            keepAliveTimer = new  javax.swing.Timer(KEEP_ALIVE_PERIOD, new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    // write the keep alive message to the stream
                    synchronized (listeners) {
                        ArrayList<DistributionRequest> clisteners = (ArrayList<DistributionRequest>) listeners.clone();
                        for (DistributionRequest listener : clisteners) {
                            if ( ! listener.deliverKeepAliveMessage()) {
                                logger.debug("remove the listener.");
                                removeListener(listener);
                            }
                        }
                    }
                }
            });
            keepAliveTimer.start();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static DataDistributer getInstance(Class<? extends DeliverySystem> c) {
        DataDistributer toReturn = singletonMap.get(c);
        if (toReturn == null)
            singletonMap.put(c, (toReturn = new DataDistributer()));
        return toReturn;
    }

    private HashMap<DistributionRequest, PreparedStatement> preparedStatements = new HashMap<DistributionRequest, PreparedStatement>();

    private ArrayList<DistributionRequest> listeners = new ArrayList<DistributionRequest>();

    private Connection db;

    private ConcurrentHashMap<DistributionRequest, DataEnumerator> candidateListeners = new ConcurrentHashMap<DistributionRequest, DataEnumerator>();

    private LinkedBlockingQueue<DistributionRequest> locker = new LinkedBlockingQueue<DistributionRequest>();

    private ConcurrentHashMap<DistributionRequest, Boolean> candidatesForNextRound = new ConcurrentHashMap<DistributionRequest, Boolean>();

    public void addListener(DistributionRequest listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                logger.warn("Adding a listener to Distributer:" + listener.toString());
                boolean needsAnd = SQLValidator.removeSingleQuotes(SQLValidator.removeQuotes(listener.getQuery())).indexOf(" where ") > 0;
                String query = listener.getQuery();
                if (needsAnd)
                    query += " AND ";
                else
                    query += " WHERE ";
                query += " timed > ? order by timed asc ";
                PreparedStatement prepareStatement = null;
                try {
                    prepareStatement = db.prepareStatement(query); //prepareStatement = StorageManager.getInstance().getConnection().prepareStatement(query);
                    prepareStatement.setMaxRows(1000); // Limit the number of rows loaded in memory.
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                preparedStatements.put(listener, prepareStatement);
                listeners.add(listener);
                addListenerToCandidates(listener);

            } else {
                logger.warn("Adding a listener to Distributer failed, duplicated listener! " + listener.toString());
            }
        }
    }


    private void addListenerToCandidates(DistributionRequest listener) {
        /**
         * Locker variable should be modified EXACTLY like candidateListeners variable.
         */
        logger.debug("Adding the listener: " + listener.toString() + " to the candidates.");
        DataEnumerator dataEnum = makeDataEnum(listener);
        if (dataEnum.hasMoreElements()) {
            candidateListeners.put(listener, dataEnum);
            locker.add(listener);
        }
    }

    private void removeListenerFromCandidates(DistributionRequest listener) {
        /**
         * Locker variable should be modified EXACTLY like candidateListeners variable.
         */
        logger.debug("Updating the candidate list [" + listener.toString() + " (removed)].");
        if (candidatesForNextRound.contains(listener)) {
            candidateListeners.put(listener, makeDataEnum(listener));
            candidatesForNextRound.remove(listener);
        } else {
            locker.remove(listener);
            candidateListeners.remove(listener);
        }
    }

    /**
     * This method only flushes one single stream element from the provided data enumerator.
     * Returns false if the flushing the stream element fails. This method also cleans the prepared statements by removing the listener completely.
     *
     * @param dataEnum
     * @param listener
     * @return
     */
    private boolean flushStreamElement(DataEnumerator dataEnum, DistributionRequest listener) {
        if (listener.isClosed()) {
            logger.debug("Flushing an stream element failed, isClosed=true [Listener: " + listener.toString() + "]");
            return false;
        }

        if (!dataEnum.hasMoreElements()) {
            logger.debug("Nothing to flush to [Listener: " + listener.toString() + "]");
            return true;
        }

        StreamElement se = dataEnum.nextElement();
        //		boolean success = true;
        boolean success = listener.deliverStreamElement(se);
        if (!success) {
            logger.debug("FLushing an stream element failed, delivery failure [Listener: " + listener.toString() + "]");
            return false;
        }
        logger.debug("Flushing an stream element succeed [Listener: " + listener.toString() + "]");
        return true;
    }

    public void removeListener(DistributionRequest listener) {
        synchronized (listeners) {
            if (listeners.remove(listener)) {
                try {
                    candidatesForNextRound.remove(listener);
                    removeListenerFromCandidates(listener);
                    preparedStatements.get(listener).close();
                    listener.close();
                    logger.warn("Removing listener completely from Distributer [Listener: " + listener.toString() + "]");
                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                } finally {
                    preparedStatements.remove(listener);
                }
            }
        }
    }

    public void consume(StreamElement se, VSensorConfig config) {
        synchronized (listeners) {
            for (DistributionRequest listener : listeners)
                if (listener.getVSensorConfig() == config) {
                    logger.debug("sending stream element " + (se == null ? "second-chance-se" : se.toString()) + " produced by " + config.getName() + " to listener =>" + listener.toString());
                    if (!candidateListeners.containsKey(listener)) {
                        addListenerToCandidates(listener);
                    } else {
                        candidatesForNextRound.put(listener, Boolean.TRUE);
                    }
                }
        }
    }

    public void run() {
        while (true) {
            try {
                if (locker.isEmpty()) {
                    logger.debug("Waiting(locked) for requests or data items, Number of total listeners: " + listeners.size());
                    locker.put(locker.take());
                    logger.debug("Lock released, trying to find interest listeners (total listeners:" + listeners.size() + ")");
                }
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }


            for (Entry<DistributionRequest, DataEnumerator> item : candidateListeners.entrySet()) {
                boolean success = flushStreamElement(item.getValue(), item.getKey());
                if (success == false)
                    removeListener(item.getKey());
                else {
                    if (!item.getValue().hasMoreElements()) {
                        removeListenerFromCandidates(item.getKey());
                        // As we are limiting the number of elements returned by the JDBC driver
                        // we consume the eventual remaining items.
                        consume(null, item.getKey().getVSensorConfig());
                    }
                }
            }
        }
    }

    public boolean vsLoading(VSensorConfig config) {
        return true;
    }

    public boolean vsUnLoading(VSensorConfig config) {
        synchronized (listeners) {
            logger.debug("Distributer unloading: " + listeners.size());
            ArrayList<DistributionRequest> toRemove = new ArrayList<DistributionRequest>();
            for (DistributionRequest listener : listeners) {
                if (listener.getVSensorConfig() == config)
                    toRemove.add(listener);
            }
            for (DistributionRequest listener : toRemove) {
                try {
                    removeListener(listener);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        return true;
    }

    private DataEnumerator makeDataEnum(DistributionRequest listener) {

        PreparedStatement prepareStatement = preparedStatements.get(listener);
        try {
            prepareStatement.setLong(1, listener.getLastVisitedTime());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return new DataEnumerator();
        }

        DataEnumerator dataEnum = new DataEnumerator(prepareStatement, false, true);
        return dataEnum;
    }

    public void release() {
        synchronized (listeners) {
            while (!listeners.isEmpty())
                removeListener(listeners.get(0));
        }
        if (keepAliveTimer != null)
            keepAliveTimer.stop();
    }

    public boolean contains(DeliverySystem delivery) {
        synchronized (listeners) {
            for (DistributionRequest listener : listeners)
                if (listener.getDeliverySystem().equals(delivery))
                    return true;
            return false;
		}

	}

}


