/*
 * Copyright 2012 Jeffrey Kleiss
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.slotted.client;

import com.google.gwt.activity.shared.Activity;
import com.google.gwt.activity.shared.ActivityMapper;
import com.google.gwt.user.client.ui.AcceptsOneWidget;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.ResettableEventBus;
import com.googlecode.slotted.client.SlottedController.RootSlotImpl;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * An internal object that holds all the data needed to correctly display a Slot.  This shouldn't be used outside
 * of the framework.
 */
public class ActiveSlot {
    /**
     * Wraps our real display to prevent an old Activity from displaying it's contents, after it is no longer
     * active.
     */
    private class ProtectedDisplay implements AcceptsOneWidget {
        private final Activity activity;
        private boolean loading = false;
        private IsWidget view;
        private boolean widgetShown = false;

        ProtectedDisplay(Activity activity) {
            this.activity = activity;
        }

        public void setWidget(IsWidget view) {
            this.view = view;
            if (!loading) {
                activityStarting = false;
            }
            if (widgetShown && this.activity == ActiveSlot.this.activity) {
                showWidget();
            }
        }

        private void showWidget() {
            widgetShown = true;
            if (view != null) {
                slot.getDisplay().setWidget(view);
                activityStarting = false;
            }
        }
    }

    private ActiveSlot parent;
    private ArrayList<ActiveSlot> children = new ArrayList<ActiveSlot>();
    private Slot slot;
    private SlottedPlace place;
    private SlottedPlace newPlace;
    private Activity activity;
    private boolean activityStarting;
    private ProtectedDisplay currentProtectedDisplay;
    private SlottedController slottedController;
    private HistoryMapper historyMapper;
    private ResettableEventBus resettableEventBus;

    public ActiveSlot(ActiveSlot parent, Slot slot, EventBus eventBus,
            SlottedController slottedController)
    {
        this.parent = parent;
        this.slot = slot;
        this.slottedController = slottedController;
        this.historyMapper = slottedController.getHistoryMapper();
        this.resettableEventBus = new ResettableEventBus(eventBus);
    }

    /**
     * Checks this Slot and child Slots to see if the current Place equals the replacement Place.  If they are the same
     * nothing is done, but if the aren't equal then {@link Activity#mayStop()}, is called to determine if the Activity
     * has a warning navigating away.
     *
     * @param newPlaces All the Places that will be navigated to, which is used to determine if the current Place
     *                  equals replacement Place.
     * @param reloadAll If true, it ignores the Place equals and calls mayStop()
     * @param warnings The list of warnings that are generated by mayStop() calls.
     */
    public void maybeGoTo(Iterable<SlottedPlace> newPlaces, boolean reloadAll,
            ArrayList<String> warnings)
    {
        boolean checkMayStop = false;
        newPlace = getPlace(newPlaces);
        if (reloadAll || !newPlace.equals(place)) {
            if (activity != null) {
                checkMayStop = true;
            }
            reloadAll = true;
        }
        if (children != null) {
            for (ActiveSlot child : children) {
                child.maybeGoTo(newPlaces, reloadAll, warnings);
            }
        }

        //Children should be checked before parent in case child mayStop() saves data.
        if (checkMayStop) {
            String warning = activity.mayStop();
            if (warning != null) {
                warnings.add(warning);
            }
        }
    }

    /**
     * Finds the ActiveSlot by determining if this ActiveSlot has the slotToFind or checking the children recursively.
     *
     * @param slotToFind The Slot instance that an ActiveSlot represents.
     * @return The ActiveSlot, or null if the slotToFind isn't in the hierarchy
     */
    public ActiveSlot findSlot(Slot slotToFind) {
        ActiveSlot found = null;
        if (slotToFind == null) {
            found = null;
        } else if (slotToFind.equals(slot)) {
            found = this;
        } else if (children != null) {
            Iterator<ActiveSlot> childIt = children.iterator();
            while (found == null && childIt.hasNext()) {
                ActiveSlot child = childIt.next();
                found = child.findSlot(slotToFind);
            }
        }
        return found;
    }

    /**
     * Stops the current Activity and all child Activities.  It also resets the EventBus to prevent memory leaks.
     */
    public void stopActivities() {
        try {
            place = null;
            if (activity != null) {
                if (activityStarting) {
                    activity.onCancel();
                } else {
                    activity.onStop();
                }
                activity = null;
                activityStarting = false;
            }
            if (children != null) {
                for (ActiveSlot child : children) {
                    child.stopActivities();
                }
                children.clear();
            }
            currentProtectedDisplay = null;
        } finally {
            resettableEventBus.removeHandlers();
        }
    }

    /**
     * Constructs the new hierarchy determining if the current Activity will change.  If it will change onStop() is called,
     * and the new Activity is constructed and start() called.  If it doesn't change, onRefresh() is called.
     *
     * @param parameters The global parameters object that should be populated during construction.
     * @param newPlaces The list of Places that will be displayed in the hierarchy.
     * @param reloadAll Will force all the Activities to be stopped and started.
     */
    public void constructStopStart(PlaceParameters parameters,
            Iterable<SlottedPlace> newPlaces, boolean reloadAll)
    {
        if (newPlace == null) {
            newPlace = getPlace(newPlaces);
        }
        historyMapper.extractParameters(newPlace, parameters);
        newPlace.setPlaceParameters(parameters);

        if (reloadAll || !newPlace.equals(place)) {
            stopActivities();
        }
        place = newPlace;
        newPlace = null;

        createChildren();

        if (slottedController.shouldStartActivity()) {
            if (activity == null) {
                getStartActivity(parameters);
            } else {
                refreshActivity(parameters);
            }
        }

        for (ActiveSlot child : children) {
            child.constructStopStart(parameters, newPlaces, reloadAll);
        }
    }

    /**
     * Gets the appropriate Place for this Slot.
     *
     * @param newPlaces The list of new Places that will be displayed.
     * @return The Place that should be displayed for this Slot
     */
    private SlottedPlace getPlace(Iterable<SlottedPlace> newPlaces) {
        for (SlottedPlace place : newPlaces) {
            boolean isRootPlace =
                    place.getParentSlot() == null || place.getParentSlot() instanceof RootSlotImpl;
            boolean isRoot = slot.getOwnerPlace() == null;
            if (isRootPlace && isRoot) {
                return place;
            }
            if (slot.equals(place.getParentSlot())) {
                return place;
            }
        }
        if (place != null) {
            return place;
        }
        return slot.getDefaultPlace();
    }

    /**
     * Gets and starts the Activity for the Place specified in class variable, and recursive calls this for its children.
     * Getting the Activity is done by checking the Place, then checking the LegacyActivityMapper.  It also creates a new
     * ResettableEventBus.
     *
     * @param parameters The global parameters for the hierarchy
     */
    private void getStartActivity(PlaceParameters parameters) {
        activity = place.getActivity();
        if (activity == null) {
            ActivityMapper mapper = slottedController.getLegacyActivityMapper();
            if (mapper == null) {
                throw new IllegalStateException("SlottedPlace.getActivity() returned null, " +
                        "and LegacyActivityMapper wasn't set.");
            }
            activity = mapper.getActivity(place);
            if (activity == null) {
                throw new IllegalStateException("SlottedPlace.getActivity() returned null, " +
                        "and LegacyActivityMapper also return null.");
            }
        }
        if (activity instanceof SlottedActivity) {
            ((SlottedActivity) activity).init(slottedController, place, parameters,
                    resettableEventBus, this);
        }
        com.google.gwt.event.shared.ResettableEventBus legacyBus =
                new com.google.gwt.event.shared.ResettableEventBus(resettableEventBus);
        activityStarting = true;
        currentProtectedDisplay = new ProtectedDisplay(activity);
        activity.start(currentProtectedDisplay, legacyBus);

        if (activity instanceof SlottedActivity) {
            for (ActiveSlot child: children) {
                Slot slot = child.getSlot();
                AcceptsOneWidget display = ((SlottedActivity) activity).getChildSlotDisplay(slot);
                if (display == null) {
                    throw new IllegalStateException(activity + " didn't correctly set the display for a Slot.");
                } else {
                    slot.setDisplay(display);
                }
            }
        } else if (!children.isEmpty()) {
            throw new IllegalStateException(place + " needs to an instance of SlottedActivity, because " +
                    "it has child slots.");
        }
    }

    /**
     * Calls onRefresh() if the current Activity is a SlottedActivity.
     *
     * @param parameters The global parameters for the hierarchy.
     */
    private void refreshActivity(PlaceParameters parameters) {
        if (activity instanceof SlottedActivity) {
            SlottedActivity slottedActivity = (SlottedActivity) activity;
            slottedActivity.init(slottedController, place, parameters,
                    resettableEventBus, this);
            slottedActivity.onRefresh();
        }
    }

    /**
     * Creates the child ActiveSlots for the Place's child Slots.
     */
    private void createChildren() {
        Slot[] childSlots = place.getChildSlots();
        if (childSlots != null && childSlots.length > 0 && children.isEmpty()) {
            for (Slot child: childSlots) {
                ActiveSlot activeSlot =  new ActiveSlot(this, child, resettableEventBus,
                        slottedController);
                children.add(activeSlot);
            }
            assert childSlots.length == children.size() : "Error creating children ActiveSlots";
        }
    }

    /**
     * Sets the loading state of this Slot.  This is used in the delayed loading feature.
     *
     * @param loading True if the loading is starting, or false if loading is complete
     * @param activity The Activity attempting to set the loading state.  If the Activity doesn't match
     *                 the current Activity, the call is ignored.
     */
    public void setLoading(boolean loading, SlottedActivity activity) {
        if (currentProtectedDisplay != null && currentProtectedDisplay.activity == activity) {
            currentProtectedDisplay.loading = loading;
            if (loading) {
                slottedController.showLoading();
            } else {
                slottedController.attemptShowViews();
            }
        }
    }

    /**
     * Recursively checks the hierarchy to find a Slot that is loading.  If more than on Slot is loading, it can't be
     * determined with Slot's place will be determined.
     *
     * @return The current Place for the Slot that is loading.
     */
    public SlottedPlace getFirstLoadingPlace() {
        if (currentProtectedDisplay == null || currentProtectedDisplay.loading) {
            return place;
        }
        if (children!= null) {
            for (ActiveSlot child: children) {
                SlottedPlace childPlace = child.getFirstLoadingPlace();
                if (childPlace != null) {
                    return childPlace;
                }
            }
        }
        return null;
    }

    /**
     * Shows all the views in the hierarchy.  This is called after all the Slots have completed loading.
     *
     * @throw IllegalStateException If any of the ActiveSlots are in a loading state.
     */
    public void showViews() {
        if (currentProtectedDisplay == null || currentProtectedDisplay.loading) {
            throw new IllegalStateException("Attempting to show a view for a loading slot:" + place);
        }
        if (activity instanceof SlottedActivity) {
            ((SlottedActivity) activity).onLoadComplete();
        }
        currentProtectedDisplay.showWidget();
        if (children!= null) {
            for (ActiveSlot child: children) {
                child.showViews();
            }
        }
    }

    /**
     * Gets the parent for this ActiveSlot.
     */
    public ActiveSlot getParent() {
        return parent;
    }

    /**
     * Gets all the child ActiveSlots that this ActiveSlot manages.
     */
    public ArrayList<ActiveSlot> getChildren() {
        return children;
    }

    /**
     * Gets the current Slot that this ActiveSlot represents
     */
    public Slot getSlot() {
        return slot;
    }

    /**
     * Gets the ResettableEventBus being used.
     * @return {@link ResettableEventBus}
     */
    public EventBus getEventBus() {
        return resettableEventBus;
    }

    /**
     * The current Place be displayed in this Slot
     */
    public SlottedPlace getPlace() {
        return place;
    }

    /**
     * The current Activity being display by this Slot
     */
    public Activity getActivity() {
        return activity;
    }

}
