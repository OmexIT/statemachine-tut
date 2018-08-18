package com.omexit.statemachinetut;

import lombok.AllArgsConstructor;
import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import java.util.Date;

@Entity(name = "tbl_orders")
@Data
@AllArgsConstructor
public class Order {
    @Id
    @GeneratedValue
    private Long id;
    private Date orderDate;
    private String state;

    public Order() {
    }

    Order(Date orderDate, StatemachineTutApplication.OrderStates orderStates) {
        this.orderDate = orderDate;
        this.setOrderStates(orderStates);
    }

    StatemachineTutApplication.OrderStates getOrderStates() {
        return StatemachineTutApplication.OrderStates.valueOf(state);
    }

    void setOrderStates(StatemachineTutApplication.OrderStates orderStates) {
        this.state = orderStates.name();
    }

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", orderDate=" + orderDate +
                ", state='" + state + '\'' +
                '}';
    }
}

