package org.fmr.findmyreads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
//@EnableCaching
public class FindMyReadsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FindMyReadsApplication.class, args);
    }

}
