:- begin_tests(starintel_field_mapping).

:- use_module('../collector-app/src/main/assets/prolog/field_mapping.pl').
:- use_module('../collector-app/src/main/assets/prolog/mapping_grammar.pl').

test(parse_decimal_geotemporal_rule) :-
    Text = "map field_ops to dataset downtown from \"2026-09-20T20:00:00Z\" to \"2026-09-21T04:00:00Z\" within 750 meters of 39.9612,-82.9988 kinds [wifi,audio,video,location] priority 80.",
    parse_mapping_rule(
        Text,
        dataset_rule(
            field_ops,
            downtown,
            StartMs,
            EndMs,
            39.9612,
            -82.9988,
            750,
            [wifi,audio,video,location],
            80
        )
    ),
    StartMs < EndMs.

test(parse_multiple_rules_without_splitting_decimal_points) :-
    Text = "map one to dataset alpha anytime within 50 meters of 39.1,-82.2 kinds [wifi] priority 10. map two to dataset beta anytime anywhere kinds any priority 1.",
    parse_mapping_rules(Text, Rules),
    length(Rules, 2).

test(radius_and_time_match, [setup(clear_rules), cleanup(clear_rules)]) :-
    assertz(dataset_rule(
        downtown_wifi,
        downtown,
        1000,
        3000,
        39.9612,
        -82.9988,
        1000,
        [wifi],
        20
    )),
    classify_observation(
        observation(obs1, wifi, 2000, 39.9613, -82.9987, _{}),
        downtown,
        downtown_wifi
    ).

test(outside_radius_rejected, [fail, setup(clear_rules), cleanup(clear_rules)]) :-
    assertz(dataset_rule(
        tiny,
        downtown,
        any,
        any,
        39.9612,
        -82.9988,
        10,
        [wifi],
        20
    )),
    classify_observation(
        observation(obs2, wifi, 2000, 40.10, -82.99, _{}),
        _Dataset,
        _Rule
    ).

test(highest_priority_wins, [setup(clear_rules), cleanup(clear_rules)]) :-
    assertz(dataset_rule(low, archive, any, any, any, any, any, [wifi], 10)),
    assertz(dataset_rule(high, active_operation, any, any, any, any, any, [wifi], 90)),
    classify_observation(
        observation(obs3, wifi, 2000, none, none, _{}),
        active_operation,
        high
    ).

clear_rules :-
    retractall(dataset_rule(_, _, _, _, _, _, _, _, _)).

:- end_tests(starintel_field_mapping).

:- initialization(main, main).

main :-
    run_tests,
    halt.
