/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.metrics.collector.realtime;

import com.ning.metrics.collector.binder.config.CollectorConfig;
import com.ning.metrics.serialization.event.Event;
import com.sun.jersey.api.json.JSONWithPadding;
import org.atmosphere.cpr.Broadcaster;

import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class NewEventListener
{
    private final Broadcaster broadcaster;
    private final String callback;
    private final String eventType;
    private final EventFormatter eventFormatter;

    public NewEventListener(final CollectorConfig config, final Broadcaster broadcaster, final String callback, final String eventType)
    {
        this.broadcaster = broadcaster;
        this.callback = callback;
        this.eventType = eventType;
        eventFormatter = new EventFormatter(config);
    }

    public void onNewEvent(final Event event)
    {
        if (eventType != null && !event.getName().equalsIgnoreCase(eventType)) {
            return;
        }

        final Response response = prepareResponse((String) eventFormatter.getFormattedEvent(event));
        broadcaster.broadcast(response);
    }

    Response prepareResponse(final String formattedEvent)
    {
        // See issue https://jersey.dev.java.net/issues/show_bug.cgi?id=461 that explain
        // why we need to manually set the content-type.
        return Response.ok(new JSONWithPadding(new GenericEntity<String>(formattedEvent)
        {
        }, callback))
            .header("Content-Type", APPLICATION_JSON)
            .build();
    }
}