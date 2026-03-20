package org.fmr.findmyreads.clRunners;

import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.repositories.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class TestUserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;

    public TestUserSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        String myEmail = "acdevs@gmail.com";

        if (!userRepository.existsByEmail(myEmail)) {
            User testUser = User.builder()
                    .email("acdevs@gmail.com")
                    .username("acdevs")
                    .passwordHash("####")
                    .build();

            userRepository.save(testUser);
            System.out.println("Dev user seeded successfully!");
        }
    }
}