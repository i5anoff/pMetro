package com.utyf.pmetro.map.routing;

import android.support.annotation.Nullable;

import com.utyf.pmetro.map.TRP_Collection;
import com.utyf.pmetro.util.StationsNum;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Stores current state of map, which relates to routing, such as start and end station and selected
 * routing preferences
 */

public class RoutingState {
    /** Set of currently active transports */
    private BitSet activeTRPs;
    /** Selected start station or null */
    private StationsNum routeStart;
    /** Selected end station or null */
    private StationsNum routeEnd;
    /** Set of computed routes from #routeStart to #routeEnd or null if no route exists */
    private Routes routes;
    /** Blocked stations, which are not allowed to travel. Cannot use set of StationsNum, as
     * StationsNum doesn't implement equals() */
    private List<StationsNum> blockedStations;

    private final TRP_Collection transports;

    private final ArrayList<Listener> listeners;
    private final RouteTimesThread backgroundThread;

    public RoutingState(TRP_Collection transports) {
        this.transports = transports;

        routeStart = routeEnd = null;
        routes = null;

        listeners = new ArrayList<>();
        backgroundThread = new RouteTimesThread();
        backgroundThread.start();

        activeTRPs = new BitSet(transports.getSize());

        blockedStations = new ArrayList<>();
    }

    /** Set active transports given their ids */
    public void setActive(int[] activeTRP) {
        activeTRPs = new BitSet(transports.getSize());
        for (int trpNum : activeTRP) {
            if (trpNum == -1)
                continue;
            if (trpNum >= transports.getSize())
                throw new IndexOutOfBoundsException(String.format(Locale.US, "Invalid transport index %d", trpNum));
            activeTRPs.set(trpNum);
        }
    }

    public boolean addActive(int trpNum) {
        activeTRPs.set(trpNum);
        return true;
    }

    public void removeActive(int trpNum) {
        if (activeTRPs.get(trpNum))
            routes = null;
        activeTRPs.clear(trpNum);
    }

    public boolean isActive(int trpNum) {
        return activeTRPs.get(trpNum);
    }

    /** Sets start station of the route and updates RoutingState */
    public void setStart(StationsNum ls) {
        // If routeStart is changed then alternative routes are possibly changed too
        if (routeStart != ls)
            routes = null;

        routeStart = ls;
        if (routeStart != null && isActive(routeStart.trp)) {
            calculateTimes(routeStart);
        }

        if (routeStart != null && routeEnd != null) {
            makeRoutes();
        }
    }

    @Nullable
    public StationsNum getStart() {
        return routeStart;
    }

    /** Sets end station of the route and updates RoutingState */
    public void setEnd(StationsNum ls) {
        // If routeEnd is changed then alternative routes are possibly changed too
        if (routeEnd != ls)
            routes = null;

        routeEnd = ls;
        if (routeStart != null && routeEnd != null) {
            makeRoutes();
        }
    }

    @Nullable
    public StationsNum getEnd() {
        return routeEnd;
    }

    public void addVia(StationsNum stn) {
        throw new RuntimeException("addVia method is Not implemented");
    }

    public void blockStation(StationsNum stn) {
        routes = null;

        blockedStations.add(stn);
        if (routeStart != null && isActive(routeStart.trp)) {
            calculateTimes(routeStart);
        }

        if (routeStart != null && routeEnd != null) {
            makeRoutes();
        }
        for (final RoutingState.Listener listener : listeners) {
            listener.onBlockedStationsChanged();
        }
    }

    public void unblockStation(StationsNum stn) {
        routes = null;

        for (Iterator<StationsNum> iterator = blockedStations.iterator(); iterator.hasNext(); ) {
            StationsNum temp = iterator.next();
            if (temp.isEqual(stn)) {
                iterator.remove();
            }
        }
        if (routeStart != null && isActive(routeStart.trp)) {
            calculateTimes(routeStart);
        }

        if (routeStart != null && routeEnd != null) {
            makeRoutes();
        }

        for (final RoutingState.Listener listener : listeners) {
            listener.onBlockedStationsChanged();
        }
    }

    public boolean isBlocked(StationsNum stn) {
        for (StationsNum temp : blockedStations) {
            if (temp.isEqual(stn))
                return true;
        }
        return false;
    }

    public List<StationsNum> getBlockedStations() {
        return blockedStations;
    }

    private void calculateTimes(StationsNum start) {
        RouteTimesThread.CalculateTimesCallback callback = new RouteTimesThread.CalculateTimesCallback() {
            @Override
            public void onComputingTimesStarted() {
                for (final RoutingState.Listener listener : listeners) {
                    listener.onComputingTimesStarted();
                }
            }

            @Override
            public void onComputingTimesProgress(StationsNum[] stationNums, float[] stationTimes) {
                for (final RoutingState.Listener listener : listeners) {
                    listener.onComputingTimesProgress(stationNums, stationTimes);
                }
            }

            @Override
            public void onComputingTimesFinished() {
                for (final RoutingState.Listener listener : listeners) {
                    listener.onComputingTimesFinished();
                }
            }
        };
        backgroundThread.calculateTimes(start, blockedStations, callback);
    }

    /** Recreates graph and calculates route */
    public void resetRoute() {
        createGraph();

        routes = null;

        if (routeStart != null && isActive(routeStart.trp)) {
            calculateTimes(routeStart);
        }
        if (routeStart != null && routeEnd != null) {
            makeRoutes();
        }
    }

    private void makeRoutes() {
        if (!isActive(routeStart.trp) || !isActive(routeEnd.trp)) {
            return; // stop if transport not active
        }
        RouteTimesThread.MakeRoutesCallback callback = new RouteTimesThread.MakeRoutesCallback() {
            @Override
            public void onMakeRoutesStarted() {
                for (final Listener listener: listeners) {
                    listener.onComputingRoutesStarted();
                }
            }

            @Override
            public void onMakeRoutesCompleted(Routes routes) {
                RoutingState.this.routes = routes;
                for (final Listener listener: listeners) {
                    listener.onComputingRoutesFinished(getBestRoutes());
                }
            }
        };
        backgroundThread.makeRoutes(routeEnd, callback);
    }

    public boolean isRouteStartSelected() {
        return routeStart != null;
    }

    public boolean isRouteEndSelected() {
        return routeEnd != null;
    }

    public boolean isRouteStartActive() {
        return isActive(routeStart.trp);
    }

    /** Returns true if the route was found between selected points */
    public boolean routeExists() {
        return routes != null;
    }

    /** Cancels selection of the route */
    public void clearRoute() {
        routeStart = null;
        routeEnd = null;
        routes = null;
    }

    private RouteInfo[] getBestRoutes() {
        if (!routeExists())
            return new RouteInfo[0];

        return routes.getAllRoutes();
    }

    public void updateRoute() {
        if (!routeExists())
            return;

        for (Listener listener : listeners) {
            listener.onRouteSelected(routes.getCurrentRoute());
        }
    }

    public void showBestRoute() {
        routes.selectBestRoute();
        for (Listener listener : listeners) {
            listener.onRouteSelected(routes.getCurrentRoute());
        }
    }

    public void showAlternativeRoute(int index) {
        routes.selectAlternativeRoute(index);
        for (Listener listener : listeners) {
            listener.onRouteSelected(routes.getCurrentRoute());
        }
    }

    private void createGraph() {
        backgroundThread.createGraph(transports, activeTRPs);
    }

    public interface Listener {
        void onComputingTimesStarted();
        void onComputingTimesProgress(final StationsNum[] stationNums, float[] stationTimes);
        void onComputingTimesFinished();
        void onComputingRoutesStarted();
        void onComputingRoutesFinished(final RouteInfo[] bestRoutes);
        void onRouteSelected(final RouteInfo route);
        void onBlockedStationsChanged();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}