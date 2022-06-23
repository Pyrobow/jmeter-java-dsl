package us.abstracta.jmeter.javadsl.wrapper.wrappers;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import us.abstracta.jmeter.javadsl.codegeneration.MethodCall;
import us.abstracta.jmeter.javadsl.codegeneration.MethodCallContext;
import us.abstracta.jmeter.javadsl.codegeneration.MethodParam;
import us.abstracta.jmeter.javadsl.codegeneration.MethodParam.NameParam;
import us.abstracta.jmeter.javadsl.codegeneration.SingleTestElementCallBuilder;

public class TestElementWrapperCallBuilder<T extends TestElement> extends
    SingleTestElementCallBuilder<T> {

  protected final Set<String> ignoredProperties = new HashSet<>(
      Arrays.asList(TestElement.NAME, TestElement.TEST_CLASS, TestElement.GUI_CLASS,
          TestElement.ENABLED, TestElement.COMMENTS));
  private final Class<? extends JMeterGUIComponent> guiClass;

  public TestElementWrapperCallBuilder(Class<T> testElementClass,
      Class<? extends JMeterGUIComponent> guiClass, List<Method> builderMethods) {
    super(testElementClass, builderMethods);
    this.guiClass = guiClass;
  }

  @Override
  protected MethodCall buildMethodCall(T testElement, MethodCallContext context) {
    ClassInstanceParam builderParam;
    T defaultInstance;
    try {
      if (testElement instanceof TestBean) {
        Class<? extends TestElement> constructorClass = testElement.getClass();
        builderParam = new ClassInstanceParam(testElementClass, constructorClass);
        defaultInstance = (T) constructorClass.newInstance();
      } else {
        String guiClassName = testElement.getPropertyAsString(TestElement.GUI_CLASS);
        Class<?> constructorClass = Class.forName(guiClassName);
        builderParam = new ClassInstanceParam(guiClass, constructorClass);
        JMeterGUIComponent guiComponent = (JMeterGUIComponent) constructorClass.newInstance();
        guiComponent.clearGui();
        defaultInstance = (T) guiComponent.createTestElement();
      }
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    MethodCall ret = buildMethodCall(
        new NameParam(testElement.getName(), defaultInstance.getName()), builderParam);
    propIter2Stream(testElement.propertyIterator())
        .filter(p -> !isPropertyWithDefaultValue(p, defaultInstance)
            && !ignoredProperties.contains(p.getName()))
        .map(PropertyParam::new)
        .filter(p -> !p.isDefault())
        .forEach(
            p -> ret.chain("prop", new MethodParam.StringParam(p.getName()), p));
    return ret;
  }

  private boolean isPropertyWithDefaultValue(JMeterProperty p, T defaultInstance) {
    return Objects.equals(p.getObjectValue(),
        defaultInstance.getProperty(p.getName()).getObjectValue());
  }

  private static Stream<JMeterProperty> propIter2Stream(PropertyIterator iter) {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iter, 0), false);
  }

  private static class PropertyParam extends MethodParam {

    private static final List<Class<? extends JMeterProperty>> LITERAL_PROPERTY_TYPES =
        Arrays.asList(NullProperty.class, BooleanProperty.class, IntegerProperty.class,
            LongProperty.class, FloatProperty.class, DoubleProperty.class);
    private final JMeterProperty prop;

    private PropertyParam(JMeterProperty prop) {
      super(Object.class, prop.getStringValue());
      this.prop = prop;
    }

    private String getName() {
      return prop.getName();
    }

    @Override
    public boolean isDefault() {
      return super.isDefault()
          || (prop instanceof CollectionProperty && ((CollectionProperty) prop).isEmpty());
    }

    @Override
    protected String buildCode(String indent) {
      if (LITERAL_PROPERTY_TYPES.stream().anyMatch(pt -> pt.isInstance(prop))) {
        return prop.getStringValue();
      } else if (prop instanceof CollectionProperty) {
        return buildCollectionCode((CollectionProperty) prop, indent);
      } else {
        return buildStringLiteral(prop.getStringValue());
      }
    }

    private String buildCollectionCode(CollectionProperty prop, String indent) {
      if (prop.size() == 1) {
        return "Collections.singletonList(" + new PropertyParam(
            prop.iterator().next()).buildCode(indent) + ")";
      }
      String childIndent = indent + MethodCall.INDENT;
      return "Arrays.asList(\n" + childIndent
          + propIter2Stream(prop.iterator())
          .map(p -> new PropertyParam(p).buildCode(childIndent))
          .collect(Collectors.joining(",\n" + childIndent))
          + "\n" + indent + ")";
    }

  }

  private static class ClassInstanceParam extends MethodParam {

    private final Class<?> constructorType;

    private ClassInstanceParam(Class<?> paramType, Class<?> constructorType) {
      super(paramType, null);
      this.constructorType = constructorType;
    }

    @Override
    protected String buildCode(String indent) {
      return "new " + constructorType.getSimpleName() + "()";
    }

  }

}