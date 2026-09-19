#include <stdio.h>
#include <vulkan/vulkan.h>

int main(void) {
  VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO };
  app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo info = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
  info.pApplicationInfo = &app;

  VkInstance instance;
  VkResult result = vkCreateInstance(&info, NULL, &instance);
  if (result != VK_SUCCESS) {
    printf("vkCreateInstance failed: %d\n", result);
    return 1;
  }

  uint32_t count = 0;
  vkEnumeratePhysicalDevices(instance, &count, NULL);
  printf("physical devices: %u\n", count);

  VkPhysicalDevice devices[8];
  if (count > 8) count = 8;
  vkEnumeratePhysicalDevices(instance, &count, devices);

  for (uint32_t i = 0; i < count; ++i) {
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(devices[i], &props);
    printf("  [%u] device=%s driver=%s api=%u.%u.%u type=%d\n", i,
           props.deviceName, props.driverName,
           VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
           VK_VERSION_PATCH(props.apiVersion), props.deviceType);
  }
  return 0;
}
