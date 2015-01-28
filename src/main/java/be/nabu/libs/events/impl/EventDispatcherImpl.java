package be.nabu.libs.events.impl;

import java.util.ArrayList;
import java.util.List;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.events.api.ResponseHandler;

/**
 * When an event is fired, it first passes through all the filters. 
 * If any filter returns "true", the event will be dropped and null is returned if applicable.
 * 
 * If it passes the filter phase, each event handler is evaluated:
 * - is it interested in this type of event? (based on class)
 * - does the subscription have a filter? If so and it returns false, the event handler is skipped
 * - get the response of the event handler and send it to the responsehandler if applicable
 * 		> does the response handler send back a non-null response? stop the event chain and return it
 * 
 * @author alex
 *
 */
public class EventDispatcherImpl implements EventDispatcher {

	private List<EventSubscriptionImpl<?, ?>> subscriptions = new ArrayList<EventSubscriptionImpl<?, ?>>();
	private List<EventSubscriptionImpl<?, Boolean>> filters = new ArrayList<EventSubscriptionImpl<?, Boolean>>();

	@Override
	public <E> void fire(E event, Object source) {
		fire(event, source, null);
	}

	/**
	 * Any filters are activated first which may stop the event in its tracks
	 * Then all subscriptions are run through in order and activated
	 * @param event
	 * @param source
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public <E, R> R fire(E event, Object source, ResponseHandler<E, R> responseHandler) {
		// filter the event
		for (EventSubscriptionImpl subscription : filters) {
			if (isInterestedIn(subscription, event, source)) {
				Boolean response = (Boolean) subscription.getHandler().handle(event);
				// the event is filtered
				if (response != null && response) {
					return null;
				}
			}
		}
		List<EventSubscriptionImpl> pipeline = getPipeline(event, source);
		for (int i = 0; i < pipeline.size(); i++) {
			EventSubscriptionImpl subscription = pipeline.get(i);
			Object response = null;
			try {
				response = subscription.getHandler().handle(event);
			}
			catch (RuntimeException e) {
				response = e;
			}
			// if this response is what you are looking for, return it
			if (responseHandler != null) {
				R handledResponse = responseHandler.handle(event, response, i == pipeline.size() - 1);
				if (handledResponse != null) {
					return handledResponse;
				}
			}
		}
		// all subscriptions were executed but no corresponding response was found, just return null
		return null;
	}
	
	@SuppressWarnings({ "rawtypes" })
	private List<EventSubscriptionImpl> getPipeline(Object event, Object source) {
		List<EventSubscriptionImpl> pipeline = new ArrayList<EventSubscriptionImpl>();
		for (EventSubscriptionImpl subscription : subscriptions) {
			if (isInterestedIn(subscription, event, source)) {
				pipeline.add(subscription);
			}
		}
		return pipeline;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private boolean isInterestedIn(EventSubscriptionImpl subscription, Object event, Object source) {
		if (subscription.getEventType().isAssignableFrom(event.getClass())
				&& (subscription.getSources().isEmpty() || subscription.getSources().contains(source) || subscription.getSources().contains(source.getClass()))) {
			if (subscription.getFilter() != null) {
				Boolean response = (Boolean) subscription.getFilter().handle(event);
				return response == null || !response;
			}
			else {
				return true;
			}
		}
		return false;
	}
	
	@Override
	public <E, R> EventSubscription<E, R> subscribe(Class<E> eventType, EventHandler<E, R> handler, Object...sources) {
		if (eventType == null) {
			throw new NullPointerException("The event type for a subscription can not be null");
		}
		synchronized(subscriptions) {
			EventSubscriptionImpl<E, R> subscription = new EventSubscriptionImpl<E, R>(this, eventType, handler, sources);
			subscriptions.add(subscription);
			return subscription;
		}
	}
	
	@Override
	public <E> EventSubscription<E, Boolean> filter(Class<E> eventType, EventHandler<E, Boolean> handler, Object...sources) {
		if (eventType == null) {
			throw new NullPointerException("The event type for a filter can not be null");
		}
		synchronized(filters) {
			EventSubscriptionImpl<E, Boolean> subscription = new EventSubscriptionImpl<E, Boolean>(this, eventType, handler, sources);
			filters.add(subscription);
			return subscription;
		}
	}
	
	<E, R> void unsubscribe(EventSubscriptionImpl<E, R> subscription) {
		subscriptions.remove(subscription);
		filters.remove(subscription);
	}
}
