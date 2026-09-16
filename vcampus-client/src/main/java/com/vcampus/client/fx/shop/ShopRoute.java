package com.vcampus.client.fx.shop;

/** Maps application-level destinations to routes owned by the native JavaFX shop. */
public final class ShopRoute {
    private ShopRoute() { }

    public static String fromCampus(String route) {
        if (route == null) return null;
        return switch (route) {
            case "shop" -> "catalog";
            case "shop-cart" -> "cart";
            case "shop-orders" -> "orders";
            case "shop-addresses" -> "addresses";
            case "shop-used" -> "used";
            default -> orderRoute(route);
        };
    }

    private static String orderRoute(String route) {
        String usedPrefix = "shop-used-order/";
        if (route.startsWith(usedPrefix)) {
            try {
                long id = Long.parseLong(route.substring(usedPrefix.length()));
                return id > 0 ? "used-order/" + id : null;
            } catch (NumberFormatException error) { return null; }
        }
        String prefix = "shop-order/";
        if (!route.startsWith(prefix)) return null;
        try {
            long id = Long.parseLong(route.substring(prefix.length()));
            return id > 0 ? "order/" + id : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
