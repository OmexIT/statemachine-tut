package com.omexit.statemachinetut;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.access.StateMachineAccess;
import org.springframework.statemachine.access.StateMachineFunction;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@SpringBootApplication
public class StatemachineTutApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatemachineTutApplication.class, args);
    }

    private static final String ORDER_ID_HEADER = "orderId";

    @Component
    class Runner implements ApplicationRunner {
        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        private final OrderService orderService;

        @Autowired
        public Runner(OrderService orderService) {
            this.orderService = orderService;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            Order order = orderService.create(new Date());
            StateMachine<OrderStates, OrderEvents> paymentStateMachine = orderService.pay(order.getId(), UUID.randomUUID().toString());
            logger.info("After calling pay(): {}", paymentStateMachine.getState().getId().name());
            logger.info("Order: {}", orderService.byId(order.getId()));

            StateMachine<OrderStates, OrderEvents> fulfilledStateMachine = orderService.fulfill(order.getId(), UUID.randomUUID().toString());
            logger.info("After calling fulfill(): {}", fulfilledStateMachine.getState().getId().name());
            logger.info("Order: {}", orderService.byId(order.getId()));

//            Long orderId = 13232L;
//            StateMachine<OrderStates, OrderEvents> machine = this.factory.getStateMachine(String.valueOf(orderId));
//            machine.getExtendedState().getVariables().putIfAbsent(ORDER_ID, orderId);
//            machine.start();
//            logger.info("current state: {}", machine.getState().getId().name());
//            machine.sendEvent(OrderEvents.PAY);
//            logger.info("current state: {}", machine.getState().getId().name());
//            Message<OrderEvents> eventsMessage = MessageBuilder
//                    .withPayload(OrderEvents.FULFILL)
//                    .setHeader("a", "b")
//                    .build();
//            machine.sendEvent(eventsMessage);
//            logger.info("current state: {}", machine.getState().getId().name());
        }
    }

    @Service
    class OrderService {
        private final OrderRepository orderRepository;
        private final StateMachineFactory<OrderStates, OrderEvents> factory;

        @Autowired
        public OrderService(OrderRepository orderRepository, StateMachineFactory<OrderStates, OrderEvents> factory) {
            this.factory = factory;
            this.orderRepository = orderRepository;
        }

        Order create(Date when) {
            return this.orderRepository.save(new Order(when, OrderStates.SUBMITTED));
        }

        Order byId(Long orderId) {
            return orderRepository.findById(orderId).get();
        }

        StateMachine<OrderStates, OrderEvents> pay(Long orderId, String paymentConfirmationNo) {
            StateMachine<OrderStates, OrderEvents> stateMachine = this.build(orderId);

            Message<OrderEvents> eventsMessage = MessageBuilder
                    .withPayload(OrderEvents.PAY)
                    .setHeader(ORDER_ID_HEADER, orderId)
                    .setHeader("paymentConfirmationNo", paymentConfirmationNo)
                    .build();
            stateMachine.sendEvent(eventsMessage);
            return stateMachine;
        }

        StateMachine<OrderStates, OrderEvents> fulfill(Long orderId, String address) {
            StateMachine<OrderStates, OrderEvents> stateMachine = this.build(orderId);

            Message<OrderEvents> eventsMessage = MessageBuilder
                    .withPayload(OrderEvents.FULFILL)
                    .setHeader(ORDER_ID_HEADER, orderId)
                    .setHeader("address", address)
                    .build();
            stateMachine.sendEvent(eventsMessage);
            return stateMachine;
        }

        private StateMachine<OrderStates, OrderEvents> build(Long orderId) {
            Order order = this.orderRepository.findById(orderId).get();
            String orderIdKey = String.valueOf(order.getId());

            StateMachine<OrderStates, OrderEvents> stateMachine = this.factory.getStateMachine(orderIdKey);
            stateMachine.stop();

            stateMachine
                    .getStateMachineAccessor()
                    .doWithAllRegions(new StateMachineFunction<StateMachineAccess<OrderStates, OrderEvents>>() {
                        @Override
                        public void apply(StateMachineAccess<OrderStates, OrderEvents> sma) {
                            sma.addStateMachineInterceptor(new StateMachineInterceptorAdapter<OrderStates, OrderEvents>() {
                                @Override
                                public void preStateChange(State<OrderStates, OrderEvents> state, Message<OrderEvents> message, Transition<OrderStates, OrderEvents> transition, StateMachine<OrderStates, OrderEvents> stateMachine) {
                                    Optional.ofNullable(message).ifPresent(msg -> {
                                        Optional.ofNullable((Long) msg.getHeaders().getOrDefault(ORDER_ID_HEADER, -1L))
                                                .ifPresent(orderId -> {
                                                    Order order = orderRepository.findById(orderId).get();
                                                    order.setOrderStates(state.getId());
                                                    orderRepository.save(order);
                                                });
                                    });
                                }
                            });
                            sma.resetStateMachine(new DefaultStateMachineContext<OrderStates, OrderEvents>(order.getOrderStates(), null, null, null));

                        }
                    });
            stateMachine.start();
            return stateMachine;
        }
    }

    enum OrderEvents {
        FULFILL,
        PAY,
        CANCEL
    }

    enum OrderStates {
        SUBMITTED,
        PAID,
        FULFILLED,
        CANCELLED
    }

    @Configuration
    @EnableStateMachineFactory
    class SimpleEnumStateMachineConfiguration extends StateMachineConfigurerAdapter<OrderStates, OrderEvents> {

        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        @Override
        public void configure(StateMachineTransitionConfigurer<OrderStates, OrderEvents> transitions) throws Exception {
            transitions.withExternal()
                    .source(OrderStates.SUBMITTED).target(OrderStates.PAID).event(OrderEvents.PAY)
                    .and()
                    .withExternal().source(OrderStates.PAID).target(OrderStates.FULFILLED).event(OrderEvents.FULFILL)
                    .and()
                    .withExternal().source(OrderStates.SUBMITTED).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL)
                    .and()
                    .withExternal().source(OrderStates.PAID).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL);
        }

        @Override
        public void configure(StateMachineStateConfigurer<OrderStates, OrderEvents> states) throws Exception {
            states.withStates()
                    .initial(OrderStates.SUBMITTED)
                    .stateEntry(OrderStates.SUBMITTED, new Action<OrderStates, OrderEvents>() {
                        @Override
                        public void execute(StateContext<OrderStates, OrderEvents> context) {
                            Long orderId = (Long) context.getExtendedState().getVariables().getOrDefault(ORDER_ID_HEADER, -1L);
                            logger.info("orderId is {}", orderId);
                            logger.info("Entering submitted state");
                        }
                    })
                    .state(OrderStates.PAID)
                    .end(OrderStates.FULFILLED)
                    .end(OrderStates.CANCELLED);
        }

        @Override
        public void configure(StateMachineConfigurationConfigurer<OrderStates, OrderEvents> config) throws Exception {
            StateMachineListenerAdapter adapter = new StateMachineListenerAdapter<OrderStates, OrderEvents>() {
                @Override
                public void stateChanged(State from, State to) {
                    logger.info("stateChanged(from: {}, to: {})", from + "", to + "");
                }
            };
            config.withConfiguration()
                    .autoStartup(false)
                    .listener(adapter);
        }
    }
}
