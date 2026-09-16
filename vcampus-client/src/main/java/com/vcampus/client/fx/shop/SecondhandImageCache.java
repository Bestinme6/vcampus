package com.vcampus.client.fx.shop;

import java.util.Objects;
import java.util.concurrent.Executor;

final class SecondhandImageCache implements AutoCloseable {
    private final SecondhandGateway gateway;private final Executor executor;
    SecondhandImageCache(SecondhandGateway gateway,Executor executor){this.gateway=Objects.requireNonNull(gateway,"gateway");this.executor=Objects.requireNonNull(executor,"executor");}
    SecondhandGateway gateway(){return gateway;}Executor executor(){return executor;}
    @Override public void close(){ }
}
