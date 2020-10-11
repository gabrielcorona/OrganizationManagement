package ut.com.coronadevelopment;

import org.junit.Test;
import com.coronadevelopment.api.MyPluginComponent;
import com.coronadevelopment.impl.MyPluginComponentImpl;

import static org.junit.Assert.assertEquals;

public class MyComponentUnitTest
{
    @Test
    public void testMyName()
    {
        MyPluginComponent component = new MyPluginComponentImpl(null);
        assertEquals("names do not match!", "myComponent",component.getName());
    }
}