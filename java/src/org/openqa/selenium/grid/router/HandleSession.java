// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.router;

import static org.openqa.selenium.remote.HttpSessionId.getSessionId;
import static org.openqa.selenium.remote.RemoteTags.SESSION_ID;
import static org.openqa.selenium.remote.RemoteTags.SESSION_ID_EVENT;
import static org.openqa.selenium.remote.http.Contents.asJson;
import static org.openqa.selenium.remote.tracing.Tags.EXCEPTION;
import static org.openqa.selenium.remote.tracing.Tags.HTTP_REQUEST;
import static org.openqa.selenium.remote.tracing.Tags.HTTP_REQUEST_EVENT;
import static org.openqa.selenium.remote.tracing.Tags.HTTP_RESPONSE;

import com.google.common.collect.ImmutableMap;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.concurrent.GuardedRunnable;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.web.ReverseProxyHandler;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.net.Urls;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.AttributeKey;
import org.openqa.selenium.remote.tracing.AttributeMap;
import org.openqa.selenium.remote.tracing.HttpTracing;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Status;
import org.openqa.selenium.remote.tracing.Tracer;

class HandleSession implements HttpHandler {

  private static final Logger LOG = Logger.getLogger(HandleSession.class.getName());

  private static class CacheEntry {
    private final SessionId sessionId;
    private final HttpClient httpClient;
    // volatile as the ConcurrentMap will not take care of synchronization
    private volatile Instant lastUse;

    public CacheEntry(SessionId sessionId, HttpClient httpClient) {
      this.sessionId = sessionId;
      this.httpClient = httpClient;
      this.lastUse = Instant.now();
    }
  }

  private final Tracer tracer;
  private final HttpClient.Factory httpClientFactory;
  private final SessionMap sessions;
  private final ConcurrentMap<URL, CacheEntry> httpClients;

  HandleSession(Tracer tracer, HttpClient.Factory httpClientFactory, SessionMap sessions) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.httpClientFactory = Require.nonNull("HTTP client factory", httpClientFactory);
    this.sessions = Require.nonNull("Sessions", sessions);

    this.httpClients = new ConcurrentHashMap<>();

    Runnable cleanUpHttpClients =
        () -> {
          Instant revalidateBefore = Instant.now().minus(1, ChronoUnit.MINUTES);
          Iterator<CacheEntry> iterator = httpClients.values().iterator();

          while (iterator.hasNext()) {
            CacheEntry entry = iterator.next();

            if (!entry.lastUse.isBefore(revalidateBefore)) {
              // the session was recently used
              return;
            }

            try {
              sessions.get(entry.sessionId);
            } catch (NoSuchSessionException e) {
              // the session is dead, remove it from the cache
              iterator.remove();

              try {
                entry.httpClient.close();
              } catch (Exception ex) {
                LOG.log(Level.WARNING, "failed to close a stale httpclient", ex);
              }
            }
          }
        };

    ScheduledExecutorService cleanUpHttpClientsCacheService =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("HandleSession - Clean up http clients cache");
              return thread;
            });
    cleanUpHttpClientsCacheService.scheduleAtFixedRate(
        GuardedRunnable.guard(cleanUpHttpClients), 1, 1, TimeUnit.MINUTES);
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    try (Span span = HttpTracing.newSpanAsChildOf(tracer, req, "router.handle_session")) {
      AttributeMap attributeMap = tracer.createAttributeMap();
      attributeMap.put(AttributeKey.HTTP_HANDLER_CLASS.getKey(), getClass().getName());

      HTTP_REQUEST.accept(span, req);
      HTTP_REQUEST_EVENT.accept(attributeMap, req);

      SessionId id =
          getSessionId(req.getUri())
              .map(SessionId::new)
              .orElseThrow(
                  () -> {
                    NoSuchSessionException exception =
                        new NoSuchSessionException("Cannot find session: " + req);
                    EXCEPTION.accept(attributeMap, exception);
                    attributeMap.put(
                        AttributeKey.EXCEPTION_MESSAGE.getKey(),
                        "Unable to execute request for an existing session: "
                            + exception.getMessage());
                    span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);
                    return exception;
                  });

      SESSION_ID.accept(span, id);
      SESSION_ID_EVENT.accept(attributeMap, id);

      try {
        HttpTracing.inject(tracer, span, req);
        HttpResponse res = loadSessionId(tracer, span, id).call().execute(req);

        HTTP_RESPONSE.accept(span, res);

        return res;
      } catch (Exception e) {
        span.setAttribute(AttributeKey.ERROR.getKey(), true);
        span.setStatus(Status.CANCELLED);

        String errorMessage =
            "Unable to execute request for an existing session: " + e.getMessage();
        EXCEPTION.accept(attributeMap, e);
        attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), errorMessage);
        span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);

        if (e instanceof NoSuchSessionException) {
          HttpResponse response = new HttpResponse();
          response.setStatus(404);
          response.setContent(
              asJson(
                  ImmutableMap.of(
                      "value",
                      req.getUri(),
                      "message",
                      errorMessage,
                      "error",
                      "invalid session id")));
          return response;
        }

        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        } else if (cause != null) {
          throw new RuntimeException(errorMessage, cause);
        } else if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new RuntimeException(errorMessage, e);
      }
    }
  }

  private Callable<HttpHandler> loadSessionId(Tracer tracer, Span span, SessionId id) {
    return span.wrap(
        () -> {
          CacheEntry cacheEntry =
              httpClients.computeIfAbsent(
                  Urls.fromUri(sessions.getUri(id)),
                  (sessionUrl) -> {
                    ClientConfig config =
                        ClientConfig.defaultConfig().baseUrl(sessionUrl).withRetries();
                    HttpClient httpClient = httpClientFactory.createClient(config);

                    return new CacheEntry(id, httpClient);
                  });
          cacheEntry.lastUse = Instant.now();
          return new ReverseProxyHandler(tracer, cacheEntry.httpClient);
        });
  }
}
