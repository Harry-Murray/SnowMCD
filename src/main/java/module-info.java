module com.snowmcd {
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;
    requires com.google.gson;
    requires java.net.http;
    requires java.logging;
    requires java.prefs;

    exports com.snowmcd;
    exports com.snowmcd.core;
    exports com.snowmcd.model;
    exports com.snowmcd.ui;

    opens com.snowmcd.model to com.google.gson;
    opens com.snowmcd.ui to javafx.graphics;
}
