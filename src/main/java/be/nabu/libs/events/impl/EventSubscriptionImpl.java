package be.nabu.libs.events.impl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;

public class EventSubscriptionImpl<E, R> implements EventSubscription<E, R> {

	private EventDispatcherImpl dispatcher;
	private EventHandler<E, Boolean> filter;
	private Class<E> eventType;
	private EventHandler<E, R> handler;
	private Set<Object> sources;
	
	EventSubscriptionImpl(EventDispatcherImpl dispatcher, Class<E> eventType, EventHandler<E, R> handler, Object...sources) {
		this.dispatcher = dispatcher;
		this.eventType = eventType;
		this.handler = handler;
		this.sources = new HashSet<Object>(Arrays.asList(sources));
	}
	
	@Override
	public void filter(EventHandler<E, Boolean> filter) {
		this.filter = filter;
	}

	@Override
	public Class<E> getEventType() {
		return eventType;
	}

	@Override
	public EventHandler<E, R> getHandler() {
		return handler;
	}

	Set<Object> getSources() {
		return sources;
	}
	
	EventHandler<E, Boolean> getFilter() {
		return filter;
	}

	@Override
	public void unsubscribe() {
		dispatcher.unsubscribe(this);
	}
}