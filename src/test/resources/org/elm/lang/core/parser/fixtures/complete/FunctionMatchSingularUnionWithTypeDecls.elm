type Msg
    = MySingleMessage


type alias Model =
    Int


update : Msg -> Model -> Model
update MySingleMessage model =
    model + 1
