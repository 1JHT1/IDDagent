package com.cropagent.model;

import lombok.Data;


@Data
public class UserRegister {
    private String username;
    private String password;
    private String bankInstitution;
}
