import okhttp3.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;

public class UseSample {

    private static String[] urls = new String[]{
            "https://www.baidu.com",
            "https://www.qq.com",
            "https://www.sina.com",
            "https://www.taobao.com",
            "https://www.sogou.com",
            "https://www.meituan.com",
            "https://www.tumblr.com",
            "https://zh.wikipedia.org/zh-hans/Wikipedia:%E9%A6%96%E9%A1%B5",
            "http://www.epochtimes.com"};

    public static void main(String[] args) {

        // get name representing the running Java virtual machine.
        String name = ManagementFactory.getRuntimeMXBean().getName();
        // get pid
        String pid = name.split("@")[0];
        System.out.println("The UseSample is running on pid: " + pid);

        for (int i = 0; i < urls.length; i++) {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(urls[i % urls.length])
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, final IOException e) {
                    System.out.println("onFailure => " + e.toString());
                }

                @Override
                public void onResponse(Call call, final Response response) throws IOException {
                    System.out.println("onResponse => " + response.toString());
                }
            });

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
