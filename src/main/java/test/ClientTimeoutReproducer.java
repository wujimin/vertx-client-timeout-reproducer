/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

public class ClientTimeoutReproducer {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimeoutReproducer.class);

  static ClassPool classPool = ClassPool.getDefault();

  static Vertx vertx;

  static HttpClient httpClient;

  static Context clientContext;

  static HttpConnection serverHttpConnection;

  static Context serverContext;

  public static void main(String[] args) throws NotFoundException, CannotCompileException {
    injectNetty();

    vertx = Vertx.vertx();

    clientContext = vertx.getOrCreateContext();

    vertx.createHttpServer()
        .connectionHandler(c -> serverHttpConnection = c)
        .requestHandler(req -> req.response().end("hi"))
        .listen(5000, ar -> {
          Thread.currentThread().setName("server");
          serverContext = Vertx.currentContext();

          if (ar.failed()) {
            LOGGER.info("listen failed", ar.cause());
            System.exit(-1);
          }

          clientContext.runOnContext(v -> {
            Thread.currentThread().setName("client");
            httpClient = vertx.createHttpClient();
            sendRequest();
          });
        });
  }

  private static void sendRequest() {
    HttpClientRequest req = httpClient.get(5000, "localhost", "/", ClientTimeoutReproducer::onResponse);
    LOGGER.info("create new client request: {}", req);
    req.setTimeout(3000)
        .exceptionHandler(e -> {
          LOGGER.info("exception, req: {}, local: {}, {}", req, req.connection().localAddress(), e.getMessage());
          System.exit(-1);
        })
        .end();
  }

  private static void onResponse(HttpClientResponse httpClientResponse) {
    httpClientResponse.bodyHandler(buf -> {
      LOGGER.info("get resp: {}, local: {}", buf, httpClientResponse.request().connection().localAddress());

      LOGGER.info("                                          ");
      LOGGER.info("************* begin reproduce ************");
      LOGGER.info("notify server close connection");
      serverContext.runOnContext(v -> serverHttpConnection.close());
    });
  }

  public static void beforeFireChannelInactiveAndDeregister() {
    if ("client".equals(Thread.currentThread().getName())) {
      LOGGER.info(
          "*** before io.netty.channel.AbstractChannel$AbstractUnsafe.fireChannelInactiveAndDeregister, mock some other thread submit a runOnContext task to send request ***");
      clientContext.runOnContext(v -> sendRequest());
    }
  }

  private static void injectNetty() throws NotFoundException, CannotCompileException {
    CtClass abstractUnsafe = classPool.get("io.netty.channel.AbstractChannel$AbstractUnsafe");

    CtMethod fireChannelInactiveAndDeregister = abstractUnsafe.getDeclaredMethod("fireChannelInactiveAndDeregister");
    fireChannelInactiveAndDeregister
        .insertBefore("test.ClientTimeoutReproducer.beforeFireChannelInactiveAndDeregister();");

    abstractUnsafe.toClass();
  }
}
