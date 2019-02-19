import json
import os
import traceback
import machine_learner.utils.dataWriter as dataWriter
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from urllib.parse import urlparse, parse_qs
from machine_learner.models import classification, regression, plLaClassification, plLaRegression


@csrf_exempt
def training_testing(request):
    """ 
    Function called when a request is received on the local server and responds appropriately.

    This function handles the requests for the learners or saving data when using the comparison run mode.
    """
    try:
        # only post request
        if request.method == 'POST':

            # Get the full url from the request
            path = request.get_full_path()
            
            # Parse the url to get specific parameters
            task_type = parse_qs(urlparse(path).query)['task-type'][0]
            mode = parse_qs(urlparse(path).query)['mode'][0]
            cycle = int(parse_qs(urlparse(path).query)['cycle'][0])

            # Clear the models/output at the first adaptation cycle (if requested)
            if cycle == 1:
                if mode == 'comparison':
                    # Remove all the collected data files before saving the first time
                    # The collected data files in question are .txt and .json files
                    dir_path = os.path.join('machine_learner', 'collected_data')
                    deleteFilesWithExt(dir_path, ['.txt', '.json'])
                elif mode == 'training' and \
                    parse_qs(urlparse(path).query)['delete-models'][0] == 'true' and \
                    task_type != 'none':
                    # Remove all the trained models for the specified mode before they are trained for the first time
                    # This is done by searching all the model files and deleting them (in the respective subdirectory per task_type)
                    dir_path = os.path.join('machine_learner', 'trained_models', task_type)
                    deleteFilesWithExt(dir_path, ['.pkl'])

            dataset = json.loads(request.body)
            response = {}

            # Take the appropriate action depending on the
            # variables of the query
            if mode == 'comparison':
                response = save_data(dataset, cycle)

            elif task_type == 'classification':
                if mode == 'training':
                    response = classification.training(
                        dataset['features'], dataset['target'], cycle)
                elif mode == 'testing':
                    response = classification.testing(dataset['features'])
                    
            elif task_type == 'pllaclassification':
                if mode == 'training':
                    response = plLaClassification.training(
                        dataset['features'], dataset['target'], cycle)
                elif mode == 'testing':
                    response = plLaClassification.testing(dataset['features'])

            elif task_type == 'regression':
                if mode == 'training':
                    response = regression.training(
                        dataset['features'], dataset['target'], cycle)
                elif mode == 'testing':
                    response = regression.testing(dataset['features'])

            elif task_type == 'pllaregression':
                if mode == 'training':
                    response = plLaRegression.training(
                        dataset['features'], dataset['target_pl'], dataset['target_la'], cycle)
                elif mode == 'testing':
                    response = plLaRegression.testing(dataset['features'])


            return JsonResponse(response)

        # If anything else then post request, send an error message back
        else:
            return JsonResponse({'message': 'only POST requests are allowed'})

    except Exception as e:
        traceback.print_tb(e.__traceback__)
        return JsonResponse({'message': 'invalid request'})



def deleteFilesWithExt(dir_path, extensions):
    '''
        Deletes all files with an extension provided in extensions under a given path.
    '''
    files = os.listdir(dir_path)
    for item in files:
        for extension in extensions:
            if item.endswith(extension):
                os.remove(os.path.join(dir_path, item))






def save_data(data, cycle):
    '''
        Saves the data retreived from the comparison mode in the simulator to a .json file.
    '''
    outputPath = os.path.join('machine_learner', 'collected_data', 'overall_adaptation_options.json')

    # TODO: add boolean parameter in url to indicate training/testing cycles
    dataWriter.appendDataMultiGoal(
        outputPath, 
        cycle, 
        cycle <= 30,
        data['packetLoss'], 
        data['energyConsumption'], 
        [] if not('latency' in data) else data['latency'],
        data['classificationBefore'],
        data['classificationAfter'],
        data['regressionPLBefore'],
        data['regressionPLAfter'],
        [] if not('regressionLABefore' in data) else data['regressionLABefore'],
        [] if not('regressionLAAfter' in data) else data['regressionLAAfter']
    )

    return {'message': 'successful'}
