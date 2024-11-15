/*
* Copyright (C) 2014 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.events.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 */
public class EventDispatcherImpl implements EventDispatcher {

	List<EventSubscriptionImpl<?, ?>> subscriptions = new ArrayList<EventSubscriptionImpl<?, ?>>();
	List<EventSubscriptionImpl<?, Boolean>> filters = new ArrayList<EventSubscriptionImpl<?, Boolean>>();
	private ExecutorService executors;
	private boolean recalculatePipelineOnRewrite = Boolean.parseBoolean(System.getProperty("event.recalculate.pipeline", "true"));
	
	public EventDispatcherImpl(ExecutorService executors) {
		this.executors = executors;
	}
	
	public EventDispatcherImpl(int poolSize) {
		executors = Executors.newFixedThreadPool(poolSize);
	}
	
	public EventDispatcherImpl() {
		// no asynchronous events
	}
	
	@Override
	public <E> void fire(final E event, final Object source) {
		// fire it asynchronously
		if (executors != null) {
			executors.submit(new Runnable() {
				@Override
				public void run() {
					fire(event, source, null);		
				}
			});
		}
		// fire it synchronously
		else {
			fire(event, source, null);
		}
	}

	@Override
	public <E, R> R fire(E event, Object source, ResponseHandler<E, R> responseHandler) {
		return fire(event, source, responseHandler, null);
	}

	/**
	 * Any filters are activated first which may stop the event in its tracks
	 * Then all subscriptions are run through in order and activated
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public <E, R> R fire(E event, Object source, ResponseHandler<E, R> responseHandler, ResponseHandler<E, E> rewriteHandler) {
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
		List<EventSubscriptionImpl> pipeline = getPipeline(event, source, null);
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
			if (rewriteHandler != null) {
				E rewrittenEvent = rewriteHandler.handle(event, response, i == pipeline.size() - 1);
				if (rewrittenEvent != null) {
					event = rewrittenEvent;
					// if we are not the last in the pipeline, recalculate the rest of the pipeline, someone may now be interested
					if (recalculatePipelineOnRewrite) {
						List<EventSubscriptionImpl> remainder = getPipeline(rewrittenEvent, source, subscription);
						// the current entry was processed as "not being the last", if we have no remainder, that would've been wrong
						// so currently we don't allow empty remainders
						if (!remainder.isEmpty()) {
							pipeline.removeAll(pipeline.subList(i + 1, pipeline.size()));
							pipeline.addAll(remainder);
						}
					}
				}
			}
		}
		// all subscriptions were executed but no corresponding response was found, just return null
		return null;
	}
	
	@SuppressWarnings({ "rawtypes" })
	private List<EventSubscriptionImpl> getPipeline(Object event, Object source, EventSubscriptionImpl after) {
		List<EventSubscriptionImpl> pipeline = new ArrayList<EventSubscriptionImpl>(subscriptions);
		Iterator<EventSubscriptionImpl> iterator = pipeline.iterator();
		boolean allow = after == null;
		while (iterator.hasNext()) {
			EventSubscriptionImpl next = iterator.next();
			// if we have an "after" to mark our start, check it
			if (!allow) {
				if (after.equals(next)) {
					allow = true;
				}
				iterator.remove();
				continue;
			}
			if (!isInterestedIn(next, event, source)) {
				iterator.remove();
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
