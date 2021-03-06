package fr.bmartel.bboxapi.router.javasample;

import com.github.kittinunf.fuel.core.FuelError;
import com.github.kittinunf.fuel.core.Handler;
import com.github.kittinunf.fuel.core.Request;
import com.github.kittinunf.fuel.core.Response;
import com.github.kittinunf.result.Result;
import fr.bmartel.bboxapi.router.BboxApiRouter;
import fr.bmartel.bboxapi.router.model.Acl;
import fr.bmartel.bboxapi.router.model.MacFilterRule;
import kotlin.Triple;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public class WifiMacFilter {

    private static CountDownLatch latch;

    public static void main(String args[]) throws InterruptedException {
        BboxApiRouter bboxapi = new BboxApiRouter();
        bboxapi.init();
        bboxapi.setPassword("admin");

        /**
         * Get wifi mac filter
         */
        //asynchronous call
        latch = new CountDownLatch(1);
        bboxapi.getWifiMacFilter(new Handler<List<Acl>>() {
            @Override
            public void failure(Request request, Response response, FuelError error) {
                error.printStackTrace();
                latch.countDown();
            }

            @Override
            public void success(Request request, Response response, List<Acl> data) {
                System.out.println(data);
                latch.countDown();
            }
        });
        latch.await();

        //synchronous call
        Triple<Request, Response, Result<List<Acl>, FuelError>> data = bboxapi.getWifiMacFilterSync();
        Request request = data.getFirst();
        Response response = data.getSecond();
        Result<List<Acl>, FuelError> wifiMacFilter = data.getThird();
        System.out.println(wifiMacFilter.get());

        /**
         * enable/disable wifi mac filter
         */
        //asynchronous call
        latch = new CountDownLatch(1);
        bboxapi.setWifiMacFilter(false, new Handler<String>() {
            @Override
            public void failure(Request request, Response response, FuelError error) {
                error.printStackTrace();
                latch.countDown();
            }

            @Override
            public void success(Request request, Response response, String data) {
                System.out.println("wifi mac filter enabled : " + response.getStatusCode());
                latch.countDown();
            }
        });
        latch.await();

        //synchronous call
        Triple<Request, Response, Result<String, FuelError>> result = bboxapi.setWifiMacFilterSync(false);
        request = result.getFirst();
        response = result.getSecond();
        System.out.println("wifi mac filter enabled : " + response.getStatusCode());

        /**
         * delete wifi mac filter rules
         */
        deleteAllRules(bboxapi, wifiMacFilter.get().get(0).getAcl().getRules().size());

        showNewSize(bboxapi);

        /**
         * create wifi mac rule
         */
        MacFilterRule rule1 = new MacFilterRule(true, "01:23:45:67:89:01", "");
        MacFilterRule rule2 = new MacFilterRule(true, "34:56:78:90:12:34", "");

        //asynchronous call
        latch = new CountDownLatch(1);
        bboxapi.createMacFilterRule(rule1, new Handler<String>() {
            @Override
            public void failure(Request request, Response response, FuelError error) {
                error.printStackTrace();
                latch.countDown();
            }

            @Override
            public void success(Request request, Response response, String data) {
                System.out.println("created rule 1 : " + response.getStatusCode());
                latch.countDown();
            }
        });
        latch.await();

        //synchronous call
        Triple<Request, Response, Result<String, FuelError>> createdResult = bboxapi.createMacFilterRuleSync(rule2);
        System.out.println("created rule 2 : " + createdResult.getSecond().getStatusCode());

        showNewSize(bboxapi);

        /**
         * update wifi mac filter rule
         */
        //asynchronous call
        latch = new CountDownLatch(1);
        bboxapi.updateMacFilterRule(1, rule2, new Handler<String>() {
            @Override
            public void failure(Request request, Response response, FuelError error) {
                error.printStackTrace();
                latch.countDown();
            }

            @Override
            public void success(Request request, Response response, String data) {
                System.out.println("updated rule 1 : " + response.getStatusCode());
                latch.countDown();
            }
        });
        latch.await();

        //synchronous call
        Triple<Request, Response, Result<String, FuelError>> updateResult = bboxapi.updateMacFilterRuleSync(2, rule1);
        System.out.println("updated rule 2 : " + updateResult.getSecond().getStatusCode());

        showNewSize(bboxapi);

        deleteAllRules(bboxapi, 2);
    }

    private static void deleteAllRules(BboxApiRouter bboxApi, int size) {
        for (int i = 0; i < size; i++) {
            Triple<Request, Response, Result<String, FuelError>> result = bboxApi.deleteMacFilterRuleSync(i + 1);
            Response response = result.getSecond();
            System.out.println("deleted rule " + (i + 1) + " : " + response.getStatusCode());
        }
    }

    private static void showNewSize(BboxApiRouter bboxApi) {
        Triple<Request, Response, Result<List<Acl>, FuelError>> result = bboxApi.getWifiMacFilterSync();
        Result<List<Acl>, FuelError> wifiMacFilter = result.getThird();
        System.out.println("new size : " + wifiMacFilter.get().get(0).getAcl().getRules().size());
    }
}
