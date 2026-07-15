package com.cropagent.model;

import lombok.Data;

@Data
public class UserLogin {
    private String username;
    private String password;
    private String bankInstitution;
}
