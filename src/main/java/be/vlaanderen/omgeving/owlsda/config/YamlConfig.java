package be.vlaanderen.omgeving.owlsda.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation indicating that a class can be loaded from a YAML resource.
 * resource: path to the YAML file on the classpath (e.g. "config/app.yml").
 * required: whether the file must be present.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface YamlConfig {
  /** path to resource on the classpath, without leading '/' */
  String resource() default "";

  /** whether the resource must be present */
  boolean required() default true;
}
