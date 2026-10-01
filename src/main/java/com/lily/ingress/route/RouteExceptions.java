package com.lily.ingress.route;

public final class RouteExceptions {

    private RouteExceptions() {
    }

    /** 404 */
    public static class RouteNotFoundException extends RuntimeException {
        public RouteNotFoundException(String message) {
            super(message);
        }
    }

    /** 409. 같은 호스트·경로를 다른 Ingress 가 이미 쓰고 있다 */
    public static class RouteConflictException extends RuntimeException {
        public RouteConflictException(String message) {
            super(message);
        }
    }
}
